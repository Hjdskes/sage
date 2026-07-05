{
  description = "My home automation system";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-26.05";
    flake-utils.url = "github:numtide/flake-utils";
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      treefmt-nix,
      clj-nix,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        cljpkgs = clj-nix.packages.${system};
        treefmt = treefmt-nix.lib.evalModule pkgs ./treefmt.nix;
        jdk = pkgs.jdk25_headless;
      in
      {
        devShells.default =
          with pkgs;
          mkShellNoCC {
            packages = [
              (clojure.override { inherit jdk; })
              clojure-lsp
              cljfmt
              clj-kondo
              (mosquitto.override { withSystemd = false; })
              nixfmt
              treefmt.config.build.wrapper
              cljpkgs.deps-lock
            ];
          };

        checks = {
          formatting = treefmt.config.build.check self;
        };

        formatter = treefmt.config.build.wrapper;

        packages = rec {
          default = sage;

          sage =
            let
              project = builtins.fromJSON (builtins.readFile ./project.json);
            in
            cljpkgs.mkCljBin {
              inherit (project) name version main-ns;
              inherit jdk;
              projectSrc =
                let
                  fs = pkgs.lib.fileset;
                in
                fs.toSource {
                  root = ./.;
                  fileset = fs.unions [
                    ./deps.edn
                    ./build.clj
                    ./project.json
                    ./deps-lock.json
                    ./flake.nix
                    ./resources
                    (fs.fileFilter (file: file.hasExt "clj" || file.hasExt "cljc") ./src)
                  ];
                };
              buildCommand = "clojure -T:build uber";
            };
        };
      }
    );
}
