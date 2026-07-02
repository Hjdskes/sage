{
  projectRootFile = "flake.nix";
  programs = {
    cljfmt.enable = true;
    mdformat.enable = true;
    nixfmt.enable = true;
    shellcheck.enable = true;
    yamlfmt.enable = true;
  };
  settings.formatter = {
    cljfmt.excludes = [ ".clj-kondo/*" ];
  };
}
