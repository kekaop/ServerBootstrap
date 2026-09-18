package dev.kekaop.ServerBootstrap;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Adapters resolve an artifact; download, verification and installation remain shared. */
public interface ArtifactSource {
    record Request(URI uri, BootstrapConfig.Auth auth) {
        @Override public String toString() { return "ArtifactRequest[redacted]"; }
    }
    Request resolve(BootstrapConfig.Source source) throws Failure;

    static ArtifactSource forType(String type) throws Failure {
        return switch (type) {
            case "github" -> new GitHub();
            case "gitlab" -> new GitLab();
            case "https", "google-drive" -> new Direct();
            default -> throw new Failure("Unsupported source type");
        };
    }
    static String encode(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20"); }
    static String repository(URI url) { return url.getPath().replaceAll("^/|/$", "").replaceAll("\\.git$", ""); }
    static String base(URI uri) { return uri.toString().replaceAll("/$", ""); }

    final class Direct implements ArtifactSource {
        public Request resolve(BootstrapConfig.Source s) { return new Request(s.url(),s.auth()); }
    }
    final class GitHub implements ArtifactSource {
        public Request resolve(BootstrapConfig.Source s) {
            if (s.mode().equals("release")) return new Direct().resolve(s);
            String api = s.apiBase() != null ? base(s.apiBase()) : s.url().getHost().equalsIgnoreCase("github.com")
                    ? "https://api.github.com" : "https://"+s.url().getAuthority()+"/api/v3";
            return new Request(URI.create(api+"/repos/"+repository(s.url())+"/zipball"+(s.ref().isEmpty() ? "" : "/"+encode(s.ref()))),s.auth());
        }
    }
    final class GitLab implements ArtifactSource {
        public Request resolve(BootstrapConfig.Source s) {
            if (s.mode().equals("release")) return new Direct().resolve(s);
            String api = s.apiBase() != null ? base(s.apiBase()) : "https://"+s.url().getAuthority()+"/api/v4";
            return new Request(URI.create(api+"/projects/"+encode(repository(s.url()))+"/repository/archive.zip"
                    +(s.ref().isEmpty() ? "" : "?sha="+encode(s.ref()))),s.auth());
        }
    }
}
