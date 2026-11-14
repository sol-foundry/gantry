// A custom authorizer for a Gantry gateway. The gateway sends it the headers of every request on a route that uses
// it; a 2xx answer lets the request through, anything else is returned to the caller as it is.
package main

import (
	"io"
	"log"
	"net/http"
	"os"
)

// authorize decides. It returns the identity headers to pass on to the app, or nil to refuse the request.
func authorize(method, uri, authorization string) map[string]string {
	if authorization == "Bearer dev-token" {
		return map[string]string{"x-user-id": "dev-user"}
	}
	return nil
}

func handle(w http.ResponseWriter, r *http.Request) {
	io.Copy(io.Discard, r.Body)
	identity := authorize(r.Header.Get("X-Forwarded-Method"), r.Header.Get("X-Forwarded-Uri"), r.Header.Get("Authorization"))
	if identity != nil {
		for name, value := range identity {
			w.Header().Set(name, value)
		}
		w.WriteHeader(http.StatusOK)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	io.WriteString(w, `{"error":"unauthorized"}`)
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	log.Printf("authorizer listening on :%s", port)
	log.Fatal(http.ListenAndServe(":"+port, http.HandlerFunc(handle)))
}
