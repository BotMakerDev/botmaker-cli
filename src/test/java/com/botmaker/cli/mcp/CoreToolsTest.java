// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreToolsTest {

    /** A real bridge that finds no core and starts none: its answer is the bridge's, told apart from a refusal. */
    @Test
    void aBridgeWithNoCoreAnswersUnreached() throws Exception {
        CoreTools tools = new CoreTools.Bridged((launch, in, out, poll) -> new McpBridge(Optional::empty, launch, in,
                out, poll));
        CoreTools.Answer answer = tools.call("reload_plugins", Map.of());
        assertTrue(answer.error() && answer.unreached(), answer.toString());
        assertTrue(answer.text().startsWith("BotMaker's core is not running"), answer.text());
    }

    @Test
    void aToolsOwnRefusalIsReached() {
        assertFalse(new CoreTools.Answer("No project is open.", true).unreached());
    }
}
