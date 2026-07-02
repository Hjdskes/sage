{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-26.05";
    flake-utils.url = "github:numtide/flake-utils";
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      treefmt-nix,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        treefmt = treefmt-nix.lib.evalModule pkgs ./treefmt.nix;
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
              (mosquitto.override { withSystemd = false; })
              nixfmt
              treefmt.config.build.wrapper
            ];
          };

        checks = {
          formatting = treefmt.config.build.check self;
        };

        formatter = treefmt.config.build.wrapper;
      }
    );
}
