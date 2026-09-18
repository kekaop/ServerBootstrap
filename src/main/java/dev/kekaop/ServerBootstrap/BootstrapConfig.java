package dev.kekaop.ServerBootstrap;

import org.bukkit.configuration.ConfigurationSection;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

public final class BootstrapConfig {
    public record Auth(String header, String value) {
        @Override public String toString() { return "Auth[redacted]"; }
    }
    public record Source(String type, String mode, URI url, URI apiBase, String ref, Auth auth) {
        @Override public String toString() { return "Source[type=" + type + ", mode=" + mode + "]"; }
    }
    public record Profile(String name, Source source, String version, String sha256, String root,
                          List<String> include, List<String> required, boolean restart, String applyMode, List<String> worlds) {
        public boolean maintenance() { return applyMode.equals("maintenance"); }
    }
    public record Limits(long downloadBytes, long extractedBytes, int entries, Duration connect, Duration download) {}
    public record Listener(boolean enabled, String bind, int port, String token) {
        @Override public String toString() { return "Listener[enabled=" + enabled + "]"; }
    }
    private final Map<String, Profile> profiles;
    private final Limits limits;
    private final Listener listener;
    private final ConfigurationSection legacy;
    private final Function<String, String> environment;
    private final String legacyBound;

    private BootstrapConfig(Map<String, Profile> profiles, Limits limits, Listener listener,
                            ConfigurationSection legacy, Function<String, String> environment, String bound) {
        this.profiles = Map.copyOf(profiles); this.limits = limits; this.listener = listener;
        this.legacy = legacy; this.environment = environment; this.legacyBound = bound;
    }
    public Limits limits() { return limits; }
    public Listener listener() { return listener; }
    public boolean isLegacy() { return legacy != null; }
    public String legacyBound() { return legacyBound; }
    public Set<String> names() { return profiles.keySet(); }

    public static BootstrapConfig parse(ConfigurationSection c, Function<String, String> env) throws Failure {
        for (String section : List.of("limits","listener","profiles"))
            if (c.contains(section) && !c.isConfigurationSection(section)) throw invalid(section+" (mapping required)");
        Limits limits = new Limits(number(c,"limits.max-download-bytes",268435456L,1,Long.MAX_VALUE),
                number(c,"limits.max-extracted-bytes",1073741824L,1,Long.MAX_VALUE),
                (int) number(c,"limits.max-entries",10000,1,1000000),
                Duration.ofSeconds(number(c,"limits.connect-timeout-seconds",15,1,3600)),
                Duration.ofSeconds(number(c,"limits.download-timeout-seconds",180,1,86400)));
        if (!c.contains("config-version")) {
            String provider = string(c,"source.provider","gitlab");
            if (!Set.of("github","gitlab").contains(provider)) throw invalid("source.provider");
            BootstrapConfig result = new BootstrapConfig(Map.of(), limits,
                    new Listener(false,"127.0.0.1",8080,""),c,env,string(c,"node-profile","none"));
            result.profile("validation");
            if (!result.legacyBound.equals("none")) name(result.legacyBound);
            return result;
        }
        number(c,"config-version",1,1,1);
        fields(c, Set.of("config-version","limits","listener","profiles"), "config");
        fields(c.getConfigurationSection("limits"), Set.of("max-download-bytes","max-extracted-bytes","max-entries",
                "connect-timeout-seconds","download-timeout-seconds"), "limits");
        fields(c.getConfigurationSection("listener"), Set.of("enabled","bind","port","token","token-env"), "listener");
        boolean enabled = bool(c,"listener.enabled",false);
        String listenerToken = secret(c,"listener",env);
        if (enabled && (listenerToken.length() < 32 || listenerToken.isBlank())) throw invalid("listener.token (minimum 32 characters)");
        String bind = string(c,"listener.bind","127.0.0.1");
        if (!Set.of("127.0.0.1","::1","0.0.0.0").contains(bind)) throw invalid("listener.bind");
        Listener listener = new Listener(enabled,bind,(int)number(c,"listener.port",8080,1,65535),listenerToken);
        ConfigurationSection section = c.getConfigurationSection("profiles");
        if (section == null) throw invalid("profiles");
        Map<String, Profile> profiles = new LinkedHashMap<>();
        for (String n : section.getKeys(false)) {
            name(n);
            ConfigurationSection p = section.getConfigurationSection(n);
            String at = "profiles." + n;
                if (p == null) throw invalid(at);
            try {
                for (String nested : List.of("source","source.auth","apply"))
                    if (p.contains(nested) && !p.isConfigurationSection(nested)) throw invalid(nested+" (mapping required)");
                fields(p,Set.of("source","version","sha256","archive-root","apply","restart"),at);
                fields(p.getConfigurationSection("source"),Set.of("type","mode","url","api-base","ref","auth"),at+".source");
                fields(p.getConfigurationSection("source.auth"),Set.of("token","token-env","header","scheme"),at+".source.auth");
                fields(p.getConfigurationSection("apply"),Set.of("include","required-files","mode","worlds"),at+".apply");
                String type = string(p,"source.type","");
                if (!Set.of("github","gitlab","https","google-drive").contains(type)) throw invalid("source.type");
                String mode = string(p,"source.mode",Set.of("github","gitlab").contains(type) ? "repository" : "direct");
                if (!(Set.of("github","gitlab").contains(type) ? Set.of("repository","release").contains(mode) : mode.equals("direct")))
                    throw invalid("source.mode");
                URI url = https(string(p,"source.url",""),"source.url");
                URI api = p.contains("source.api-base") ? https(string(p,"source.api-base",""),"source.api-base") : null;
                if (api!=null && api.getRawQuery()!=null) throw invalid("source.api-base (query not allowed)");
                String ref = string(p,"source.ref","");
                if (mode.equals("repository") && ref.isBlank()) throw invalid("source.ref");
                if (ref.length() > 256 || ref.chars().anyMatch(ch -> ch < 32)) throw invalid("source.ref");
                if (mode.equals("repository") && (url.getRawQuery() != null || url.getRawPath() == null
                        || !url.getPath().matches("/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+/?"))) throw invalid("source.url");
                if (type.equals("github") && mode.equals("repository") && url.getPath().replaceAll("/$", "").split("/").length != 3)
                    throw invalid("source.url");
                if (mode.equals("repository")) path(url.getPath().replaceAll("^/|/$", ""),"source.url");
                String token = secret(p,"source.auth",env);
                String header = string(p,"source.auth.header",type.equals("gitlab") ? "PRIVATE-TOKEN" : "Authorization");
                if (!Set.of("Authorization","PRIVATE-TOKEN","JOB-TOKEN").contains(header)) throw invalid("source.auth.header");
                String scheme = string(p,"source.auth.scheme",header.equals("Authorization") ? "Bearer" : "");
                if (!Set.of("","Bearer","token").contains(scheme)) throw invalid("source.auth.scheme");
                Auth auth = new Auth(header,token.isEmpty() ? "" : (scheme.isEmpty() ? "" : scheme+" ")+token);
                String version = string(p,"version","");
                if (!version.matches("[A-Za-z0-9][A-Za-z0-9._/+\\-]{0,127}")) throw invalid("version (quote numeric versions)");
                String sha = string(p,"sha256","").toLowerCase(Locale.ROOT);
                if (!sha.isEmpty() && !sha.matches("[a-f0-9]{64}")) throw invalid("sha256");
                String root = string(p,"archive-root","auto");
                if (!Set.of("auto",".").contains(root)) path(root,"archive-root");
                List<String> include = paths(p,"apply.include",List.of("plugins"));
                if (include.isEmpty()) throw invalid("apply.include");
                for (String includePath : include) if (SafePaths.protectedPath(includePath)) throw invalid("apply.include (protected path)");
                List<String> required = paths(p,"apply.required-files",List.of());
                for (String file : required) if (include.stream().noneMatch(i -> SafePaths.under(file,i)) || SafePaths.protectedPath(file))
                    throw invalid("apply.required-files (outside include or protected)");
                String applyMode=string(p,"apply.mode","direct");
                if (!Set.of("direct","maintenance").contains(applyMode)) throw invalid("apply.mode");
                List<String> worlds=paths(p,"apply.worlds",List.of());
                if (applyMode.equals("maintenance")) {
                    if (worlds.isEmpty() || worlds.size()>1000 || worlds.stream().map(SafePaths::key).distinct().count()!=worlds.size() || !new HashSet<>(include).equals(new HashSet<>(worlds)))
                        throw invalid("apply.worlds (must match include; complete world directories only)");
                    for (String world : worlds) if (!WorldSwap.allowedWorld(world)) throw invalid("apply.worlds (invalid or reserved directory)");
                } else if (!worlds.isEmpty()) throw invalid("apply.worlds (requires maintenance mode)");
                profiles.put(n,new Profile(n,new Source(type,mode,url,api,ref,auth),version,sha,root,include,required,bool(p,"restart",true),applyMode,worlds));
            } catch (Failure e) { throw new Failure(at + ": " + e.getMessage()); }
        }
        return new BootstrapConfig(profiles,limits,listener,null,env,"none");
    }

    public Profile profile(String n) throws Failure {
        name(n);
        if (legacy == null) {
            Profile p = profiles.get(n);
            if (p == null) throw new Failure("Unknown profile: " + n);
            return p;
        }
        String type = string(legacy,"source.provider","gitlab"), base = "source." + type;
        URI api = https(string(legacy,base+".api-base",type.equals("github") ? "https://api.github.com" : "https://gitlab.com/api/v4"),base+".api-base");
        String template = string(legacy,base+(type.equals("github") ? ".repo-template" : ".project-path-template"),"%s");
        if (!template.contains("%s") || template.replace("%s","").contains("%")) throw invalid(base+".template");
        String repo = template.replace("%s",n);
        if (type.equals("github")) repo = string(legacy,base+".owner","") + "/" + repo;
        if (!repo.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")) throw invalid(base+".repository");
        String token = secret(legacy,base,environment);
        Source source = new Source(type,"repository",URI.create("https://"+api.getAuthority()+"/"+repo),api,"",
                new Auth(type.equals("github") ? "Authorization" : "PRIVATE-TOKEN",token.isEmpty() ? "" : (type.equals("github") ? "Bearer " : "")+token));
        return new Profile(n,source,"legacy-default","","auto",List.of("plugins","world","world_nether","world_the_end"),List.of(),true,"direct",List.of());
    }

    private static List<String> paths(ConfigurationSection c, String field, List<String> fallback) throws Failure {
        Object value = c.get(field);
        if (value == null) return fallback;
        if (!(value instanceof List<?> list)) throw invalid(field);
        List<String> result = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof String s)) throw invalid(field);
            path(s,field); result.add(s);
        }
        return List.copyOf(result);
    }
    private static void path(String path, String field) throws Failure {
        try { SafePaths.relative(path); } catch (Failure e) { throw invalid(field); }
    }
    static URI https(String value, String field) throws Failure {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) { throw invalid(field+" (HTTPS URL required; no userinfo or fragment)"); }
    }
    private static String secret(ConfigurationSection c, String at, Function<String,String> env) throws Failure {
        String token = string(c,at+".token",""), variable = string(c,at+".token-env","");
        if (!token.isEmpty() && !variable.isEmpty()) throw invalid(at+" (choose token or token-env)");
        if (!variable.isEmpty()) {
            if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) throw invalid(at+".token-env");
            token = env.apply(variable);
            if (token == null || token.isBlank()) throw invalid(at+".token-env (environment variable missing)");
        }
        if (token.contains("${") || token.chars().anyMatch(ch -> ch < 32 || ch > 126)) throw invalid(at+".token");
        return token;
    }
    private static String string(ConfigurationSection c, String f, String d) throws Failure {
        Object v = c.get(f); if (v == null) return d;
        if (!(v instanceof String s)) throw invalid(f); return s;
    }
    private static long number(ConfigurationSection c, String f, long d, long min, long max) throws Failure {
        Object v = c.get(f); if (v == null) return d;
        if (!(v instanceof Integer || v instanceof Long)) throw invalid(f);
        long n = ((Number)v).longValue(); if (n < min || n > max) throw invalid(f); return n;
    }
    private static boolean bool(ConfigurationSection c, String f, boolean d) throws Failure {
        Object v = c.get(f); if (v == null) return d;
        if (!(v instanceof Boolean b)) throw invalid(f); return b;
    }
    private static void name(String n) throws Failure {
        if (n == null || n.equals("none") || !n.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw invalid("profile name (none is reserved)");
    }
    private static void fields(ConfigurationSection c, Set<String> allowed, String at) throws Failure {
        if (c != null && !allowed.containsAll(c.getKeys(false))) throw invalid(at+" (unknown field)");
    }
    private static Failure invalid(String field) { return new Failure("Invalid field: " + field); }
}
