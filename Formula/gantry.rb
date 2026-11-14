# Homebrew formula, kept in this repo (the repo doubles as its tap):
#   brew tap sol-foundry/tap https://github.com/sol-foundry/gantry && brew trust --formula sol-foundry/tap/gantry && brew install gantry
# Homebrew loads formulas from outside its official repositories only once they're trusted.
# The release workflow (.github/workflows/release.yml) updates url and sha256 for every release.
class Gantry < Formula
  desc "Per-app local infrastructure (databases, queues, caches) in Docker"
  homepage "https://github.com/sol-foundry/gantry"
  url "https://github.com/sol-foundry/gantry/releases/download/v0.3.0/gantry-0.3.0.tar.gz"
  sha256 "bf0725459efe3a5d6ac8cab0b84aceb4950842d86bf5745d7dcbcd1434b4b147"
  license "Apache-2.0"

  depends_on "openjdk"

  def install
    libexec.install Dir["*"]
    # Homebrew's own Java, whatever JAVA_HOME the user has set (it may be older than Gantry needs). GANTRY_LAUNCHER
    # is the stable opt path, so agents connected with `gantry mcp install` survive `brew upgrade`.
    env = Language::Java.java_home_env.merge(GANTRY_LAUNCHER: opt_bin/"gantry")
    (bin/"gantry").write_env_script libexec/"bin/gantry", env
  end

  test do
    assert_match "postgres", shell_output("#{bin}/gantry services")
  end
end
