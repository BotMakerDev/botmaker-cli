package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DepTagTest {

    @Test
    void aModuleBeingReleasedIsPinnedToTheVersionThisRunIsCutting() {
        // The load-bearing case: that tag does not exist yet when the downstream's release commit is written, so
        // reading the repository here would pin the PREVIOUS release and publish a downstream resolving an
        // upstream it was not built against. No checkout is touched, which is why the path is a dummy.
        assertEquals("v1.2.0", DepTag.of(Path.of("/nowhere"), Module.SHARED,
                Optional.of(new Version(1, 2, 0))));
    }

    @Test
    void aModuleWithNoTagAndNoReleaseIsRefusedRatherThanPinnedToAGuess() {
        // /nowhere has no git repository, so latest_version answers nothing — the same state as a module
        // that has genuinely never been tagged, and the same refusal.
        ReleaseRefusal refused = assertThrows(ReleaseRefusal.class,
                () -> DepTag.of(Path.of("/nowhere"), Module.SHARED, Optional.empty()));
        assertEquals("botmaker-shared: no tag to pin a downstream build to", refused.getMessage());
    }
}
