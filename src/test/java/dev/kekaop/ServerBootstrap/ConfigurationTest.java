package dev.kekaop.ServerBootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path temp;
    @Test void validVersionedProfile() throws Exception {
        var c=TestSupport.config();
        assertEquals("v1",c.profile("lobby").version()); assertEquals(268435456,c.limits().downloadBytes());
        assertFalse(c.listener().enabled()); assertFalse(c.isLegacy());
    }
    @ParameterizedTest @ValueSource(strings={"http://example.org/a.zip","https://user:password@example.org/a.zip","https://example.org/a.zip#fragment","not-a-url"})
    void unsafeUrlsAreRedacted(String url) {
        Failure error=assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("https://example.org/profile.zip",url)));
        assertTrue(error.getMessage().contains("profiles.lobby")); assertTrue(error.getMessage().contains("source.url"));
        assertFalse(error.getMessage().contains("password"));
    }
    @ParameterizedTest @ValueSource(strings={"config-version: 2","config-version: '1'","config-version: true"})
    void invalidSchema(String field) { assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("config-version: 1",field))); }
    @Test void malformedHashAndVersion() {
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML+"    sha256: nope\n"));
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("version: 'v1'","version: 1")));
    }
    @Test void requiredFilesMustBeAppliedAndSafe() {
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("plugins/test.txt","../outside")));
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("plugins/test.txt","world/level.dat")));
    }
    @Test void rejectsUnknownFieldsAndBadTypes() {
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("lobby:","none:")));
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML+"limits: wrong\n"));
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("restart: false","restart: nope")));
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML+"    sh256: secret\n"));
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("[plugins]","plugins")));
    }
    @Test void authenticationIsRedactedAndLoadedFromEnvironment() throws Exception {
        var c=TestSupport.config(TestSupport.YAML.replace("      type: https","      type: https\n      auth:\n        token-env: ARTIFACT_TOKEN"));
        assertTrue(c.profile("lobby").source().auth().value().contains("env-secret"));
        assertFalse(c.profile("lobby").toString().contains("env-secret"));
    }
    @Test void rejectsDuplicateYamlKeysWithoutLeakingValues() throws Exception {
        Path p=temp.resolve("config.yml"); Files.writeString(p,TestSupport.YAML+"    version: secret-never-log\n");
        Failure f=assertThrows(Failure.class,()->ConfigFiles.load(p));
        assertFalse(f.getMessage().contains("secret-never-log"));
    }
    @Test void listenerRequiresStrongToken() {
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML+"listener:\n  enabled: true\n  token: short\n"));
    }
    @Test void legacyGithubAndGitlabRemainResolvable() throws Exception {
        for (String provider : new String[]{"github","gitlab"}) {
            var c=TestSupport.config("""
                    node-profile: lobby
                    source:
                      provider: %s
                      github:
                        owner: org
                        repo-template: 'profile-%%s'
                        token: github-secret
                      gitlab:
                        project-path-template: 'group/servers/%%s'
                        token: gitlab-secret
                    """.formatted(provider));
            assertTrue(c.isLegacy()); assertFalse(c.listener().enabled()); assertEquals("lobby",c.legacyBound());
            String uri=ArtifactSource.forType(provider).resolve(c.profile("lobby").source()).uri().toString();
            assertEquals(provider.equals("github") ? "https://api.github.com/repos/org/profile-lobby/zipball"
                    : "https://gitlab.com/api/v4/projects/group%2Fservers%2Flobby/repository/archive.zip",uri);
        }
    }
    @Test void sourceAdaptersResolveRepoRefsAndReleaseAssets() throws Exception {
        var github=TestSupport.config(TestSupport.YAML.replace("type: https","type: github\n      ref: release/test").replace("https://example.org/profile.zip","https://github.com/org/project.git"));
        assertEquals("https://api.github.com/repos/org/project/zipball/release%2Ftest",ArtifactSource.forType("github").resolve(github.profile("lobby").source()).uri().toString());
        assertThrows(Failure.class,()->TestSupport.config(TestSupport.YAML.replace("type: https","type: github\n      ref: main").replace("https://example.org/profile.zip","https://github.com/../project")));
        var gitlab=TestSupport.config(TestSupport.YAML.replace("type: https","type: gitlab\n      ref: v1").replace("https://example.org/profile.zip","https://gitlab.example.org/group/sub/repo"));
        assertEquals("https://gitlab.example.org/api/v4/projects/group%2Fsub%2Frepo/repository/archive.zip?sha=v1",ArtifactSource.forType("gitlab").resolve(gitlab.profile("lobby").source()).uri().toString());
        for (String type : new String[]{"github","gitlab","google-drive","https"}) {
            var c=TestSupport.config(TestSupport.YAML.replace("type: https","type: "+type+(type.equals("github") || type.equals("gitlab") ? "\n      mode: release" : "")));
            assertEquals("https://example.org/profile.zip",ArtifactSource.forType(type).resolve(c.profile("lobby").source()).uri().toString());
        }
    }
}
