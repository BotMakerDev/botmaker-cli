package com.botmaker.cli.gallery;

import com.botmaker.cli.Console;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Regenerates {@value GalleryCatalog#CATALOG} and {@value GalleryCatalog#INDEX} — what the gallery's
 * {@code index.yml} runs after a merge. It replaced {@code tools/build-index.sh}.
 *
 * <p>In this module rather than in the gallery for the reason {@link GalleryGate} is: the rules deciding what
 * the generated files say — which listings are vetted, which fields an older Studio is given — are the same
 * rules the gate and {@code bot publish} reason with, and a {@code jq} copy of them would be a second
 * implementation.
 *
 * <pre>java -cp … com.botmaker.cli.gallery.CatalogBuilder &lt;gallery-root&gt;</pre>
 *
 * <p>Exit code: {@code 0} written, {@code 1} the gallery could not be read, {@code 2} bad invocation.
 */
public final class CatalogBuilder {

    private CatalogBuilder() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Console console = new Console(false);
        if (args.length != 1) {
            console.error("usage: CatalogBuilder <gallery-root>");
            return 2;
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        try {
            GalleryCatalog catalog = GalleryCatalog.read(root);
            catalog.problems().forEach(console::warn);
            Files.writeString(root.resolve(GalleryCatalog.CATALOG), catalog.catalogJson());
            Files.writeString(root.resolve(GalleryCatalog.INDEX), catalog.legacyIndexJson());
            long vetted = catalog.listings().stream().filter(l -> l.tier() == Tier.VETTED).count();
            console.out(GalleryCatalog.CATALOG + ": " + catalog.listings().size() + " listing(s); "
                    + GalleryCatalog.INDEX + ": " + vetted + " vetted.");
            return 0;
        } catch (IOException e) {
            console.error(e.getMessage());
            return 1;
        }
    }
}
