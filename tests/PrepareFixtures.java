import dev.couchreader.importer.EpubValidator;
import java.io.File;

/** Uses the production importer, rather than duplicating its sanitization in the browser test. */
public final class PrepareFixtures {
    public static void main(String[] args) throws Exception {
        for (int version : new int[] {2, 3}) {
            new EpubValidator().validate(new File("app/src/androidTest/assets/fixtures/reading-" + version + ".epub"),
                new File("app/build/browser-fixtures/" + version), "fixture.epub");
        }
    }
}
