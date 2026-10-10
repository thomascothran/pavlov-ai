{ pkgs, inputs, ... }:

let
  clojure-mcp-light = inputs.clojure-mcp-light;

  # clojure-mcp-light's bb.edn plus the Maven mirrors from deps.edn, so the
  # clj-paren-repair and clj-nrepl-eval scripts fetch their deps from Google's
  # Maven Central mirror first. bb resolves :paths relative to the real location
  # of bb.edn, so it is copied next to a src symlink. Keep :deps in sync with
  # ${clojure-mcp-light}/bb.edn.
  clmlBbEdn = pkgs.writeText "bb.edn" ''
    {:paths ["src"]
     :deps {parinferish/parinferish {:mvn/version "0.8.0"}
            dev.weavejester/cljfmt {:mvn/version "0.15.5"}}
     :mvn/repos {"central" {:url "https://maven-central.storage-download.googleapis.com/maven2/"}
                 "maven-central" {:url "https://repo1.maven.org/maven2/"}}}
  '';
  clmlBb = pkgs.runCommand "clojure-mcp-light-bb" { } ''
    mkdir $out
    cp ${clmlBbEdn} $out/bb.edn
    ln -s ${clojure-mcp-light}/src $out/src
  '';
in
{
  cachix.enable = false;

  # No C/C++ toolchain (gcc, binutils, libc headers) in the shell.
  stdenv = pkgs.stdenvNoCC;

  # Language servers are off; enable them in devenv.local.nix if your editor
  # uses them.
  languages.clojure.enable = true;
  languages.clojure.lsp.enable = false;
  languages.java.lsp.enable = false;

  packages = [
    pkgs.babashka
    pkgs.git
  ];

  processes.clj.exec = "clj -A:dev:test -X dev/go!";

  scripts.clj-test.exec = ''
    clj -X:test
  '';

  scripts.deploy.exec = ''
    clj -X:test
    clj -T:build ci
    env $(cat ~/.secrets/.clojars | xargs) clj -T:build deploy
  '';

  scripts.test-watch.exec = ''
    clj -X:test :watch? true
  '';

  scripts.clj-build.exec = ''
    clj -T:build ci
  '';

  scripts.clj-paren-repair.exec = ''
    exec bb --config ${clmlBb}/bb.edn -m clojure-mcp-light.paren-repair "$@"
  '';

  scripts.clj-nrepl-eval.exec = ''
    exec bb --config ${clmlBb}/bb.edn -m clojure-mcp-light.nrepl-eval "$@"
  '';

  enterTest = ''
    clj -X:test
  '';
}
