// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.publish;

/** Why a publish stopped, worded for the author: the next step it did not take, and what to do instead. */
public final class Refusal extends Exception {

    public Refusal(String message) {
        super(message);
    }
}
