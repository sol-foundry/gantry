package dev.gantry.gateway

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import dev.gantry.engine.GantryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApisixRendererTest {
    private val ctx = RenderContext(app = "citadel", defaultUpstream = "http://localhost:8080")

    private fun allRoutes(
        config: GatewayConfig,
        context: RenderContext = ctx,
    ): List<YamlMap> {
        val root = Yaml.default.parseToYamlNode(renderApisixRoutes(config, context)) as YamlMap
        return (root.get<YamlList>("routes")!!).items.map { it as YamlMap }
    }

    /** The HTTP routes, without the WebSocket handshake route that always comes first. */
    private fun routes(
        config: GatewayConfig,
        context: RenderContext = ctx,
    ): List<YamlMap> = allRoutes(config, context).filter { it.get<YamlScalar>("id")!!.content != WEBSOCKET_ROUTE }

    private fun socketRoute(config: GatewayConfig): YamlMap = allRoutes(config).first()

    private fun YamlNode.str(): String = (this as YamlScalar).content

    private fun YamlMap.map(key: String): YamlMap? = get<YamlMap>(key)

    private fun YamlMap.strings(key: String): List<String> = get<YamlList>(key)!!.items.map { it.str() }

    private fun YamlMap.plugin(name: String): YamlMap? = map("plugins")!!.map(name)

    private fun config(yml: String) = parseGatewayConfig(yml)

    private val protectedYml =
        """
        authorizers:
          fb:
            type: firebase
            projectId: demo
        routes:
          - name: portal
            match: { path: /portal/** }
            auth: fb
            headers:
              x-user-id: "{{uid}}"
          - name: health
            match: { path: /actuator/** }
            auth: public
            headers:
              x-source: gateway
              x-user-id: "{{uid}}"
        """.trimIndent()

    @Test
    fun `config enables standalone data plane`() {
        val root = Yaml.default.parseToYamlNode(renderApisixConfig()) as YamlMap
        val dep = root.map("deployment")!!
        assertEquals("data_plane", dep.get<YamlScalar>("role")!!.content)
        assertEquals("yaml", dep.map("role_data_plane")!!.get<YamlScalar>("config_provider")!!.content)
        val apisix = root.map("apisix")!!
        assertEquals("9080", apisix.get<YamlScalar>("node_listen")!!.content)
        assertEquals("false", apisix.get<YamlScalar>("enable_admin")!!.content)
        assertEquals(
            listOf("request-id", "proxy-rewrite", "forward-auth", "cors", "file-logger"),
            root.strings("plugins"),
        )
        // No stream proxy: the default stream plugins include syslog, which fails to load without prometheus.
        assertEquals(emptyList(), root.strings("stream_plugins"))
    }

    @Test
    fun `transparent proxy when no routes`() {
        val all = routes(GatewayConfig())
        assertEquals(1, all.size)
        val r = all.single()
        assertEquals("default", r.get<YamlScalar>("id")!!.content)
        assertEquals("/*", r.get<YamlScalar>("uri")!!.content)
        assertNull(r.plugin("forward-auth"))
        val up = r.map("upstream")!!
        assertEquals("http", up.get<YamlScalar>("scheme")!!.content)
        assertEquals("node", up.get<YamlScalar>("pass_host")!!.content)
        assertEquals("roundrobin", up.get<YamlScalar>("type")!!.content)
        val nodes = up.map("nodes")!!
        assertEquals(listOf("host.docker.internal:8080"), nodes.entries.keys.map { it.content })
        assertEquals(
            "1",
            nodes.entries.values
                .single()
                .str(),
        )
    }

    @Test
    fun `upstream scheme and default ports`() {
        val https = routes(GatewayConfig(upstream = "https://api.example.com")).single().map("upstream")!!
        assertEquals("https", https.get<YamlScalar>("scheme")!!.content)
        assertEquals(
            listOf("api.example.com:443"),
            https
                .map("nodes")!!
                .entries.keys
                .map { it.content },
        )
        val plain = routes(GatewayConfig(upstream = "http://svc")).single().map("upstream")!!
        assertEquals(
            listOf("svc:80"),
            plain
                .map("nodes")!!
                .entries.keys
                .map { it.content },
        )
    }

    @Test
    fun `upstream precedence is route then config then context`() {
        val cfg =
            GatewayConfig(
                upstream = "http://cfg:1",
                routes = listOf(Route("a", Match(path = "/a/**"), upstream = "http://route:2")),
                default = RouteAction(upstream = "http://dflt:3"),
            )
        val all = routes(cfg)

        fun host(r: YamlMap) =
            r
                .map("upstream")!!
                .map("nodes")!!
                .entries.keys
                .single()
                .content
        assertEquals("route:2", host(all[0]))
        assertEquals("dflt:3", host(all[1]))
        assertEquals("cfg:1", host(routes(cfg.copy(routes = emptyList(), default = RouteAction())).single()))
    }

    @Test
    fun `upstream with a path is rejected`() {
        assertFailsWith<GantryException> { routes(GatewayConfig(upstream = "http://localhost:8080/api")) }
    }

    @Test
    fun `routes keep their order`() {
        val all = routes(config(protectedYml))
        assertEquals(listOf("portal", "health", "default"), all.map { it.get<YamlScalar>("id")!!.content })
        val priorities = all.map { it.get<YamlScalar>("priority")!!.content.toInt() }
        assertTrue(priorities.zipWithNext().all { (a, b) -> a > b }, "priorities $priorities")
        assertEquals("0", all.last().get<YamlScalar>("priority")!!.content)
    }

    @Test
    fun `route matches host path and methods`() {
        val cfg =
            config(
                """
                routes:
                  - name: api
                    match: { host: api.example.com, path: /v1/*, methods: [GET, POST] }
                  - name: any
                    match: { path: /x }
                """.trimIndent(),
            )
        val (api, any) = routes(cfg)
        assertEquals(listOf("api.example.com"), api.strings("hosts"))
        assertEquals(listOf("GET", "POST"), api.strings("methods"))
        assertEquals("/*", api.get<YamlScalar>("uri")!!.content)
        val v = (api.get<YamlList>("vars")!!.items.single() as YamlList).items.map { it.str() }
        assertEquals(listOf("uri", "~~", globToRegex("/v1/*").pattern), v)
        assertNull(any.get<YamlList>("hosts"))
        assertNull(any.get<YamlList>("methods"))
    }

    @Test
    fun `client identity headers are stripped before auth`() {
        val cfg = config(protectedYml)
        val all = routes(cfg)
        assertEquals(3, all.size)
        // The public health route sets x-user-id itself (blank), which replaces a client copy; it is not also removed.
        val expected = listOf(cfg.stripHeaders, cfg.stripHeaders - "x-user-id", cfg.stripHeaders)
        all.forEachIndexed { i, r ->
            assertEquals(
                expected[i],
                r.plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"),
                r.get<YamlScalar>("id")!!.content,
            )
        }
        val custom = routes(cfg.copy(stripHeaders = listOf("x-a", "x-b")))
        assertEquals(listOf("x-a", "x-b", "x-user-id"), custom[0].plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"))
        assertEquals(listOf("x-a", "x-b"), custom[1].plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"))
        assertEquals(listOf("x-a", "x-b"), custom[2].plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"))
    }

    @Test
    fun `every route gets a request id and a call log`() {
        routes(config(protectedYml)).forEach { r ->
            assertEquals("X-Request-Id", r.plugin("request-id")!!.get<YamlScalar>("header_name")!!.content)
            val log = r.plugin("file-logger")!!
            assertEquals("/gantry-log/calls.log", log.get<YamlScalar>("path")!!.content)
            assertEquals("true", log.get<YamlScalar>("include_req_body")!!.content)
            assertEquals("true", log.get<YamlScalar>("include_resp_body")!!.content)
        }
    }

    @Test
    fun `every route proxies websocket upgrades`() {
        val all = routes(config(protectedYml)) + routes(GatewayConfig())
        all.forEach { r -> assertEquals("true", r.get<YamlScalar>("enable_websocket")?.content, r.toString()) }
    }

    @Test
    fun `protected route calls gantry-auth with the route name`() {
        val portal = routes(config(protectedYml)).first()
        val fa = portal.plugin("forward-auth")!!
        assertEquals("http://gantry-auth:9100/authorize?route=portal", fa.get<YamlScalar>("uri")!!.content)
        assertEquals("POST", fa.get<YamlScalar>("request_method")!!.content)
        assertEquals(
            listOf("Authorization", "Cookie", "X-Request-Id", "X-Forwarded-Host", "X-User-Timezone"),
            fa.strings("request_headers"),
        )
        assertEquals(DEFAULT_STRIP, fa.strings("upstream_headers"))
        assertEquals(listOf("WWW-Authenticate"), fa.strings("client_headers"))
        assertEquals("10000", fa.get<YamlScalar>("timeout")!!.content)
    }

    @Test
    fun `upstream_headers fall back to the strip list`() {
        val cfg = config("routes:\n  - name: r\n    match: { path: /r }\n    auth: deny\n")
        val fa = routes(cfg).first().plugin("forward-auth")!!
        assertEquals(cfg.stripHeaders, fa.strings("upstream_headers"))
        assertEquals("http://gantry-auth:9100/authorize?route=r", fa.get<YamlScalar>("uri")!!.content)
    }

    @Test
    fun `default action authenticates under the name default`() {
        val cfg = config("authorizers:\n  fb: { type: firebase, projectId: p }\ndefault:\n  auth: fb\n")
        val d = routes(cfg).single()
        assertEquals("http://gantry-auth:9100/authorize?route=default", d.plugin("forward-auth")!!.get<YamlScalar>("uri")!!.content)
    }

    @Test
    fun `custom authorizer route calls its container`() {
        val cfg =
            config(
                """
                authorizers:
                  mine: { type: custom, container: checker, tokenFrom: "header:X-Api-Token" }
                  ext: { type: custom, url: "http://localhost:7000/verify" }
                routes:
                  - { name: a, match: { path: /a }, auth: mine }
                  - { name: b, match: { path: /b }, auth: ext }
                """.trimIndent(),
            )
        val (a, b) = routes(cfg, ctx.copy(authorizerPorts = mapOf("checker" to 9999)))
        val fa = a.plugin("forward-auth")!!
        assertEquals("http://auth-checker:9999/", fa.get<YamlScalar>("uri")!!.content)
        assertTrue("X-Api-Token" in fa.strings("request_headers"))
        assertEquals("http://host.docker.internal:7000/verify", b.plugin("forward-auth")!!.get<YamlScalar>("uri")!!.content)
        val dflt = routes(cfg).first().plugin("forward-auth")!!
        assertEquals("http://auth-checker:8080/", dflt.get<YamlScalar>("uri")!!.content)
    }

    @Test
    fun `public route blanks templated identity headers and sets constants`() {
        val health = routes(config(protectedYml))[1]
        assertNull(health.plugin("forward-auth"))
        val headers = health.plugin("proxy-rewrite")!!.map("headers")!!
        val set = headers.map("set")!!
        assertEquals("gateway", set.get<YamlScalar>("x-source")!!.content)
        assertEquals("", set.get<YamlScalar>("x-user-id")!!.content)
        // proxy-rewrite applies remove after set: a header it sets must not also be removed, or the value is lost.
        // The set alone already replaces any copy the client sent.
        assertEquals(DEFAULT_STRIP - "x-user-id", headers.strings("remove"))
    }

    @Test
    fun `public route keeps a fixed strip header it sets, matching names in any case`() {
        val cfg =
            config(
                """
                routes:
                  - name: pub
                    match: { path: /pub/** }
                    headers: { X-Gateway-Auth-Strategy: public }
                """.trimIndent(),
            )
        val headers = routes(cfg).first().plugin("proxy-rewrite")!!.map("headers")!!
        assertEquals("public", headers.map("set")!!.get<YamlScalar>("X-Gateway-Auth-Strategy")!!.content)
        assertEquals(DEFAULT_STRIP - "x-gateway-auth-strategy", headers.strings("remove"))
    }

    @Test
    fun `rendered routes never contain template braces`() {
        val cfg =
            config(
                """
                authorizers:
                  fb: { type: firebase, projectId: p }
                routes:
                  - name: pub
                    match: { path: /pub/** }
                    headers: { a: "{{uid}}", b: "{{ctx.userId}}", c: "x-{{secret:K}}" }
                  - name: prot
                    match: { path: /prot/** }
                    auth: fb
                    headers: { a: "{{uid}}", b: "{{secret:K}}" }
                default:
                  headers: { d: "{{email}}" }
                """.trimIndent(),
            )
        assertFalse("{{" in renderApisixRoutes(cfg, ctx))
    }

    @Test
    fun `rewrite variants produce the right regex_uri`() {
        val cfg =
            config(
                """
                routes:
                  - { name: add, match: { path: /a/** }, rewrite: { addPrefix: /portal } }
                  - { name: strip, match: { path: /b/** }, rewrite: { stripPrefix: /b } }
                  - { name: rep, match: { path: /c }, rewrite: { replace: /health } }
                  - { name: none, match: { path: /d } }
                """.trimIndent(),
            )
        val all = routes(cfg)

        fun regex(r: YamlMap) =
            r
                .plugin("proxy-rewrite")!!
                .get<YamlList>("regex_uri")
                ?.items
                ?.map { it.str() }
        assertEquals(listOf("^(.*)$", "/portal$1"), regex(all[0]))
        // The bare prefix becomes "/", never an empty URI; a longer name that only starts with it is left alone.
        assertEquals(listOf("^/b(?:/(.*))?$", "/$1"), regex(all[1]))
        assertEquals(listOf("^.*$", "/health"), regex(all[2]))
        assertNull(regex(all[3]))
    }

    @Test
    fun `file ends with END`() {
        assertTrue(renderApisixRoutes(GatewayConfig(), ctx).endsWith("\n#END\n"))
    }

    @Test
    fun `cors only when configured`() {
        assertNull(routes(GatewayConfig()).single().plugin("cors"))
        val cfg = GatewayConfig(cors = Cors(origins = listOf("http://localhost:3000"), allowHeaders = listOf("x-a"), maxAge = 60))
        val cors = routes(cfg).single().plugin("cors")
        assertNotNull(cors)
        assertEquals("http://localhost:3000", cors.get<YamlScalar>("allow_origins")!!.content)
        assertEquals("false", cors.get<YamlScalar>("allow_credential")!!.content)
        assertEquals("x-a", cors.get<YamlScalar>("allow_headers")!!.content)
        assertEquals("60", cors.get<YamlScalar>("max_age")!!.content)
    }

    @Test
    fun `cors origins are only ever the listed ones`() {
        // Without credentials a wildcard origin is harmless and stays a plain "*".
        val open = routes(GatewayConfig(cors = Cors())).single().plugin("cors")!!
        assertEquals("false", open.get<YamlScalar>("allow_credential")!!.content)
        assertEquals("*", open.get<YamlScalar>("allow_origins")!!.content)
        // Even a config that skipped validation never turns "*" into "**" (reflect any origin) with credentials on.
        val unsafe = routes(GatewayConfig(cors = Cors(credentials = true))).single().plugin("cors")!!
        assertEquals("*", unsafe.get<YamlScalar>("allow_origins")!!.content)
    }

    @Test
    fun `cors with credentials allows any method and header but only listed origins`() {
        val cors = routes(GatewayConfig(cors = Cors(origins = listOf("http://a"), credentials = true))).single().plugin("cors")!!
        assertEquals("true", cors.get<YamlScalar>("allow_credential")!!.content)
        assertEquals("http://a", cors.get<YamlScalar>("allow_origins")!!.content)
        assertEquals("**", cors.get<YamlScalar>("allow_methods")!!.content)
        assertEquals("**", cors.get<YamlScalar>("allow_headers")!!.content)
        assertEquals("**", cors.get<YamlScalar>("expose_headers")!!.content)
        val mapped =
            routes(
                GatewayConfig(
                    cors = Cors(origins = listOf("http://a"), credentials = true, allowHeaders = listOf("*"), exposeHeaders = listOf("*")),
                ),
            ).single().plugin("cors")!!
        assertEquals("**", mapped.get<YamlScalar>("allow_headers")!!.content)
        assertEquals("**", mapped.get<YamlScalar>("expose_headers")!!.content)
        val explicit =
            routes(
                GatewayConfig(cors = Cors(origins = listOf("http://a"), credentials = true, allowHeaders = listOf("x-a", "x-b"))),
            ).single().plugin("cors")!!
        assertEquals("x-a,x-b", explicit.get<YamlScalar>("allow_headers")!!.content)
    }

    @Test
    fun `protected route also removes the headers it sets`() {
        val cfg =
            config(
                """
                authorizers:
                  fb: { type: firebase, projectId: p }
                routes:
                  - name: r
                    match: { path: /r }
                    auth: fb
                    headers: { x-tenant: "{{ctx.tenant}}", X-User-Id: "{{uid}}" }
                default:
                  auth: fb
                  headers: { x-other: "{{ctx.o}}" }
                """.trimIndent(),
            )
        val (r, d) = routes(cfg)
        assertEquals(DEFAULT_STRIP + "x-tenant", r.plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"))
        assertEquals(DEFAULT_STRIP + "x-other", d.plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"))
    }

    @Test
    fun `config uses the uri router so route priority alone decides`() {
        // The default radixtree_host_uri router tries every route with hosts before any without, whatever the priority.
        val root = Yaml.default.parseToYamlNode(renderApisixConfig()) as YamlMap
        assertEquals(
            "radixtree_uri",
            root
                .map("apisix")!!
                .map("router")!!
                .get<YamlScalar>("http")!!
                .content,
        )
    }

    @Test
    fun `emitted ids, methods and hosts match the APISIX schema`() {
        val cfg =
            config(
                """
                authorizers:
                  fb: { type: firebase, projectId: p }
                routes:
                  - { name: portal.v1_a-b, match: { path: /p/**, methods: [get, Post, DELETE] }, auth: fb }
                  - { name: hook, match: { host: "*.webhook.localhost", path: /** } }
                  - { name: ipv6, match: { host: "[::1]:8080", path: /** } }
                """.trimIndent(),
            )
        val id = Regex("^[a-zA-Z0-9-_.]+$")
        val host = Regex("^\\*?[0-9a-zA-Z-._\\[\\]:]+$")
        val methods = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT", "TRACE", "PURGE")
        val all = routes(cfg)
        all.forEach { r ->
            val routeId = r.get<YamlScalar>("id")!!.content
            assertTrue(id.matches(routeId) && routeId.length <= 64, routeId)
            r.get<YamlList>("methods")?.items?.forEach { assertTrue(it.str() in methods, it.str()) }
            r.get<YamlList>("hosts")?.items?.forEach { assertTrue(host.matches(it.str()), it.str()) }
        }
        assertEquals(listOf("GET", "POST", "DELETE"), all[0].strings("methods"))
    }

    @Test
    fun `methods are emitted once each, whatever their case`() {
        val cfg = GatewayConfig(routes = listOf(Route("r", Match(path = "/r/**", methods = listOf("GET", "get", "Post", "POST", "GET")))))
        assertEquals(listOf("GET", "POST"), routes(cfg)[0].strings("methods"))
    }

    @Test
    fun `the route name is url-encoded in the forward-auth query`() {
        val jwt = Authorizer(type = "jwt", jwksUrl = "http://k")
        val cfg = GatewayConfig(authorizers = mapOf("j" to jwt), routes = listOf(Route("a&b c", Match(), auth = "j")))
        assertEquals(
            "http://gantry-auth:9100/authorize?route=a%26b%20c",
            routes(cfg)
                .first()
                .plugin("forward-auth")!!
                .get<YamlScalar>("uri")!!
                .content,
        )
    }

    @Test
    fun `every route holds idle upstream connections for an hour`() {
        val cfg =
            config(
                """
                authorizers:
                  fb: { type: firebase, projectId: p }
                routes:
                  - { name: a, match: { path: /a/** }, auth: fb }
                  - { name: b, match: { path: /b/** }, auth: deny }
                  - { name: c, match: { path: /c/** } }
                """.trimIndent(),
            )
        routes(cfg).forEach { r ->
            val timeout = r.map("timeout")!!
            assertEquals(listOf("6", "3600", "3600"), listOf("connect", "send", "read").map { timeout.get<YamlScalar>(it)!!.content })
        }
        // forward-auth keeps its own 10 s budget.
        assertEquals(
            "10000",
            routes(cfg)
                .first()
                .plugin("forward-auth")!!
                .get<YamlScalar>("timeout")!!
                .content,
        )
    }

    @Test
    fun `forward-auth answers 503 when the authorizer cannot be reached`() {
        val fa = routes(config(protectedYml)).first().plugin("forward-auth")!!
        assertEquals("503", fa.get<YamlScalar>("status_on_error")!!.content)
    }

    @Test
    fun `a custom container authorizer with an unset env secret is asked of gantry-auth instead`() {
        val cfg =
            config(
                """
                authorizers:
                  mine:
                    type: custom
                    container: checker
                    env: { API_KEY: "{{secret:API_KEY}}", MODE: strict }
                  other:
                    type: custom
                    container: checker
                    env: { TOKEN: "x-{{secret:TOKEN}}" }
                routes:
                  - { name: a, match: { path: /a/** }, auth: mine }
                  - { name: b, match: { path: /b/** }, auth: other }
                default:
                  auth: mine
                """.trimIndent(),
            )

        fun uris(context: RenderContext) = routes(cfg, context).map { it.plugin("forward-auth")!!.get<YamlScalar>("uri")!!.content }
        val expected =
            listOf(
                "http://gantry-auth:9100/authorize?route=a",
                "http://auth-checker:8080/",
                "http://gantry-auth:9100/authorize?route=default",
            )
        assertEquals(expected, uris(ctx.copy(unsetSecrets = setOf("API_KEY"))))
        assertEquals(List(3) { "http://auth-checker:8080/" }, uris(ctx))
    }

    @Test
    fun `proxy-rewrite never carries an empty remove list, headers block or plugin config`() {
        // APISIX's schema needs at least one item in each, and skips the whole route otherwise.
        val cfg =
            config(
                """
                routes:
                  - name: all-set
                    match: { path: /a/** }
                    headers:
                      x-user-id: "{{uid}}"
                      X-Gateway-User-Email: "{{email}}"
                      X-Gateway-Auth-Strategy: public
                      x-internal-api-key: "{{secret:K}}"
                      X-Error-Code: "{{errorCode}}"
                """.trimIndent(),
            )
        val allSet = routes(cfg).first().plugin("proxy-rewrite")!!.map("headers")!!
        assertNull(allSet.get<YamlList>("remove"))
        assertEquals(5, allSet.map("set")!!.entries.size)

        val bare = routes(GatewayConfig(stripHeaders = emptyList())).single()
        assertNull(bare.plugin("proxy-rewrite"))
        val rewriteOnly =
            routes(GatewayConfig(stripHeaders = emptyList(), routes = listOf(Route("r", Match(), rewrite = Rewrite(addPrefix = "/p")))))
        assertNull(rewriteOnly.first().plugin("proxy-rewrite")!!.map("headers"))
    }

    @Test
    fun `a config without shared headers renders as pinned`() {
        // Byte-identical to the renderer before shared headers, except two intended changes: a route that needs a
        // login lists the strip headers in upstream_headers too, not only its own; and the WebSocket route comes first.
        fun fixture(name: String) = ApisixRendererTest::class.java.getResource("/gateway/$name")!!.readText()
        assertEquals(fixture("golden-apisix.yaml"), renderApisixRoutes(config(fixture("golden-gateway.yml")), ctx))
    }

    @Test
    fun `websocket handshakes on any path go to the relay before every route`() {
        val all = allRoutes(config(protectedYml))
        val socket = socketRoute(config(protectedYml))

        assertEquals(WEBSOCKET_ROUTE, socket.get<YamlScalar>("id")!!.content)
        assertTrue(
            all.drop(1).all { it.get<YamlScalar>("priority")!!.content.toInt() < socket.get<YamlScalar>("priority")!!.content.toInt() },
        )
        val vars = (socket.get<YamlList>("vars")!!.items.single() as YamlList).items.map { it.str() }
        assertEquals(listOf("http_upgrade", "~*", "^websocket$"), vars)
        assertEquals(
            listOf("gateway-ws:8080"),
            socket
                .map("upstream")!!
                .map("nodes")!!
                .entries.keys
                .map { it.content },
        )
        // Without a websocket block, sockets are public.
        assertNull(socket.plugin("forward-auth"))
    }

    @Test
    fun `websocket handshakes use the configured authorizer, steps and headers`() {
        val socket =
            socketRoute(
                config(
                    protectedYml +
                        """

                        websocket:
                          auth: fb
                          headers:
                            x-user-id: "{{uid}}"
                        """.trimIndent(),
                ),
            )

        val auth = socket.plugin("forward-auth")!!
        assertEquals("http://gantry-auth:9100/authorize?route=websocket", auth.get<YamlScalar>("uri")!!.content)
        assertTrue("x-user-id" in auth.strings("upstream_headers"))
    }

    private val sharedYml =
        """
        headers:
          x-gateway-auth-strategy: firebase
          x-user-id: "{{uid}}"
          x-tenant: acme
        authorizers:
          fb:
            type: firebase
            projectId: demo
        routes:
          - name: portal
            match: { path: /portal/** }
            auth: fb
            headers:
              X-Tenant: "{{tenant}}"
          - name: health
            match: { path: /actuator/** }
            headers:
              x-source: gateway
        """.trimIndent()

    @Test
    fun `a public route sets the shared headers, blanking templated ones`() {
        val health = routes(config(sharedYml))[1]
        val headers = health.plugin("proxy-rewrite")!!.map("headers")!!
        val set =
            headers
                .map("set")!!
                .entries.entries
                .associate { (k, v) -> k.content to v.str() }
        assertEquals(mapOf("x-gateway-auth-strategy" to "firebase", "x-user-id" to "", "x-tenant" to "acme", "x-source" to "gateway"), set)
        assertNull(health.plugin("forward-auth"))
    }

    @Test
    fun `a protected route takes the shared headers from gantry-auth`() {
        val (portal, _, default) = routes(config(sharedYml).let { it.copy(default = it.default.copy(auth = "fb")) })
        assertEquals(DEFAULT_STRIP + "X-Tenant", portal.plugin("forward-auth")!!.strings("upstream_headers"))
        assertEquals(DEFAULT_STRIP + "x-tenant", default.plugin("forward-auth")!!.strings("upstream_headers"))
    }

    @Test
    fun `shared header names are stripped from the client on every route`() {
        val cfg = config(sharedYml)
        routes(cfg).forEach { r ->
            val headers = r.plugin("proxy-rewrite")!!.map("headers")!!
            // A public route sets every shared header, which replaces a client copy; any other route removes it.
            val gone =
                (headers.get<YamlList>("remove")?.items?.map { it.str() } ?: emptyList()) +
                    (
                        headers
                            .map("set")
                            ?.entries
                            ?.keys
                            ?.map { it.content } ?: emptyList()
                    )
            listOf("x-gateway-auth-strategy", "x-user-id", "x-tenant").forEach { h ->
                assertTrue(gone.any { it.equals(h, ignoreCase = true) }, "$h on ${r.get<YamlScalar>("id")!!.content}: $gone")
            }
        }
    }

    @Test
    fun `a custom authorizer route passes on the strip headers and its own, so shared headers do not hide the authorizer's`() {
        val cfg =
            config(
                """
                headers:
                  X-User-Id: "{{uid}}"
                  x-tenant: acme
                authorizers:
                  box: { type: custom, container: checker }
                  ext: { type: custom, url: "http://localhost:7000/verify" }
                routes:
                  - name: box
                    match: { path: /box/** }
                    auth: box
                    headers:
                      x-extra: "1"
                  - name: ext
                    match: { path: /ext/** }
                    auth: ext
                """.trimIndent(),
            )
        val (box, ext) = routes(cfg)
        val strip = listOf("X-User-Id") + DEFAULT_STRIP.drop(1)
        assertEquals(strip + listOf("x-tenant", "x-extra"), box.plugin("forward-auth")!!.strings("upstream_headers"))
        assertEquals(strip + "x-tenant", ext.plugin("forward-auth")!!.strings("upstream_headers"))
        // The client's copies are still removed before the authorizer's answer is applied.
        assertEquals(DEFAULT_STRIP + listOf("x-tenant", "x-extra"), box.plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"))
        assertEquals(DEFAULT_STRIP + "x-tenant", ext.plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"))
    }

    @Test
    fun `every route that needs a login passes on the strip headers and its effective ones`() {
        // An existing config: a gantry-auth route with its own headers now lists the strip headers too (intended).
        val (portal) = routes(config(protectedYml))
        assertEquals(DEFAULT_STRIP, portal.plugin("forward-auth")!!.strings("upstream_headers"))
        val shared =
            config(
                """
                headers:
                  x-tenant: acme
                  X-User-Id: "{{uid}}"
                authorizers:
                  fb: { type: firebase, projectId: demo }
                routes:
                  - name: r
                    match: { path: /r/** }
                    auth: fb
                    headers:
                      x-extra: "{{email}}"
                default:
                  auth: fb
                """.trimIndent(),
            )
        val (r, default) = routes(shared)
        val strip = listOf("X-User-Id") + DEFAULT_STRIP.drop(1)
        assertEquals(strip + listOf("x-tenant", "x-extra"), r.plugin("forward-auth")!!.strings("upstream_headers"))
        assertEquals(strip + "x-tenant", default.plugin("forward-auth")!!.strings("upstream_headers"))
        // The client's copies are still removed first.
        assertEquals(DEFAULT_STRIP + listOf("x-tenant", "x-extra"), r.plugin("proxy-rewrite")!!.map("headers")!!.strings("remove"))
        // A header the route names keeps the route's spelling, in the strip list's place.
        val spelled =
            config(
                """
                routes:
                  - name: s
                    match: {path: /s}
                    auth: deny
                    headers:
                      X-Error-Code: x
                      X-New: y
                """.trimIndent(),
            )
        assertEquals(
            listOf("x-user-id", "x-gateway-user-email", "x-gateway-auth-strategy", "x-internal-api-key", "X-Error-Code", "X-New"),
            routes(spelled).first().plugin("forward-auth")!!.strings("upstream_headers"),
        )
    }
}
