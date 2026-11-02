class Gantry < Formula
  desc "Local Postgres, MySQL, Valkey, SQS, Kafka and Temporal per service, in Docker"
  homepage "https://github.com/sol-foundry/gantry"
  url "https://github.com/sol-foundry/gantry/archive/refs/tags/v0.1.0.tar.gz"
  sha256 "f05e31a46804e3bc57ee78d9735da81c68e9f3a977581151f7c037ab7a459001"
  license "Apache-2.0"

  livecheck do
    url :stable
    strategy :github_latest
  end

  depends_on "gradle" => :build
  depends_on "node" => :build
  depends_on "openjdk@17"

  def install
    java_home = formula_opt_prefix("openjdk@17")
    ENV["JAVA_HOME"] = java_home

    # The build reads its version from version.properties; the source archive carries the release's.
    inreplace "version.properties", /^version=.*/, "version=#{version}"
    system "gradle", "installDist", "--no-daemon", "-x", "test",
           "-Porg.gradle.java.installations.auto-download=false",
           "-Porg.gradle.java.installations.paths=#{java_home}"

    libexec.install Dir["build/install/gantry/*"]
    rm libexec/"bin/gantry.bat"
    env = Language::Java.java_home_env("17").merge(GANTRY_LAUNCHER: opt_bin/"gantry")
    (bin/"gantry").write_env_script libexec/"bin/gantry", env
  end

  test do
    assert_match "postgres", shell_output("#{bin}/gantry services")
    request = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
    output = pipe_output("#{bin}/gantry mcp serve nosuchapp", "#{request}\n")
    assert_match "\"version\":\"#{version}\"", output
  end
end
