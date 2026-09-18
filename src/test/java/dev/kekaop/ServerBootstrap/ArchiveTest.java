package dev.kekaop.ServerBootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveTest {
    @TempDir Path temp;
    private ArchiveVerifier.Plan extract(Path zip) throws Exception {
        return new ArchiveVerifier().extract(zip,temp.resolve(UUID.randomUUID().toString()),TestSupport.profile(),TestSupport.config().limits());
    }
    @Test void rootAndRepositoryWrappersProduceSamePlan() throws Exception {
        for (String prefix : List.of("","owner-repo-hash/")) {
            var plan=extract(TestSupport.zip(temp,Map.of(prefix+"plugins/test.txt","hello",prefix+"README.md","ignored")));
            assertEquals(Set.of("plugins/test.txt"),plan.files().keySet());
            assertEquals("hello",Files.readString(plan.files().get("plugins/test.txt")));
        }
    }
    @ParameterizedTest @ValueSource(strings={"../escape.txt","plugins/../../escape.txt","/escape.txt","C:/escape.txt","plugins\\evil.txt","plugins/test.txt:stream","plugins/CON.txt","plugins/./test.txt","plugins//test.txt","plugins/evil. ","//server/share/file"})
    void forbiddenPathsNeverEscape(String name) throws Exception {
        Path archive=TestSupport.zip(temp,Map.of("plugins/test.txt","valid",name,"attack"));
        assertThrows(Failure.class,()->extract(archive)); assertFalse(Files.exists(temp.resolve("escape.txt")));
    }
    @Test void missingRequiredFile() throws Exception { assertThrows(Failure.class,()->extract(TestSupport.zip(temp,Map.of("plugins/other.txt","hello")))); }
    @Test void htmlAndTruncatedZipRejected() throws Exception {
        Path html=temp.resolve("html.zip"); Files.writeString(html,"<html>Login to Google Drive</html>");
        assertThrows(Failure.class,()->extract(html));
        Path zip=TestSupport.zip(temp,Map.of("plugins/test.txt","hello")); byte[] bytes=Files.readAllBytes(zip);
        Files.write(zip,Arrays.copyOf(bytes,bytes.length-12)); assertThrows(Failure.class,()->extract(zip));
    }
    @Test void crcMismatchIsDetected() throws Exception {
        Path zip=temp.resolve("stored.zip"); byte[] data="hello".getBytes(); CRC32 crc=new CRC32(); crc.update(data);
        try (ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry entry=new ZipEntry("plugins/test.txt"); entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCrc(crc.getValue());
            out.putNextEntry(entry); out.write(data); out.closeEntry();
        }
        byte[] bytes=Files.readAllBytes(zip); bytes[30+"plugins/test.txt".length()]^=1; Files.write(zip,bytes);
        assertThrows(Failure.class,()->extract(zip));
    }
    @Test void duplicateCaseAndParentAliasesRejected() throws Exception {
        assertThrows(Failure.class,()->extract(TestSupport.zip(temp,Map.of("plugins/test.txt","a","plugins/Test.txt","b"))));
        assertThrows(Failure.class,()->extract(TestSupport.zip(temp,Map.of("plugins/A/test.txt","a","plugins/a/other.txt","b"))));
    }
    @Test void sizeAndEntryLimitsApplyToWholeArchive() throws Exception {
        var defaults=TestSupport.config().limits();
        var limits=new BootstrapConfig.Limits(100000,10,1,defaults.connect(),defaults.download());
        Path zip=TestSupport.zip(temp,Map.of("plugins/test.txt","x".repeat(100)));
        assertThrows(Failure.class,()->new ArchiveVerifier().extract(zip,temp.resolve("expanded"),TestSupport.profile(),limits));
        Path many=TestSupport.zip(temp,Map.of("plugins/test.txt","a","ignored.txt","b"));
        assertThrows(Failure.class,()->new ArchiveVerifier().extract(many,temp.resolve("entries"),TestSupport.profile(),limits));
    }
    @Test void protectedFilesAreNotApplied() throws Exception {
        var plan=extract(TestSupport.zip(temp,Map.of("plugins/test.txt","ok","plugins/ServerBootstrap/config.yml","secret",
                "plugins/ServerBootstrap-2.jar","jar","plugins/NodeMetrics/config.yml","config")));
        assertEquals(Set.of("plugins/test.txt"),plan.files().keySet());
    }
    @Test void sha256MatchesAndMismatchFails() throws Exception {
        Path zip=TestSupport.zip(temp,Map.of("plugins/test.txt","test"));
        var download=new ArtifactDownloader.Download(Files.size(zip),TestSupport.digest(zip));
        assertDoesNotThrow(()->ArtifactDownloader.verify(download,TestSupport.digest(zip).toUpperCase(Locale.ROOT)));
        assertThrows(Failure.class,()->ArtifactDownloader.verify(download,"0".repeat(64)));
    }
}
