{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-26.05";
    flake-utils.url = "github:numtide/flake-utils";
  };


  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default =
          with pkgs;
          mkShellNoCC {
            packages = [
              (clojure.override { jdk = jdk25_headless; })
              clojure-lsp
              cljfmt
              clj-kondo
              mosquitto
              nixfmt
            ];
          };
      }
    );
}
