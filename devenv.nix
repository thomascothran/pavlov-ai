{ pkgs, inputs, ... }:

let
  clojure-mcp-light = inputs.clojure-mcp-light;
in
{
  cachix.enable = false;

  languages.clojure.enable = true;

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
    exec bb --config ${clojure-mcp-light}/bb.edn -m clojure-mcp-light.paren-repair "$@"
  '';

  scripts.clj-nrepl-eval.exec = ''
    exec bb --config ${clojure-mcp-light}/bb.edn -m clojure-mcp-light.nrepl-eval "$@"
  '';

  enterTest = ''
    clj -X:test
  '';
}
