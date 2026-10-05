# Why there is no JavaFX in it

`SlotEditor.create` returns `javafx.scene.Node`, so `javafx-controls` is on the **compile** classpath at
`provided` scope — and nothing here ever constructs a `Node`.

That is a real limitation, not a tidy one, and the `EDITORS` check states it: **an editor's predicate is
checked and its node is not.** Building a node needs a live JavaFX toolkit; carrying one would make this a
per-OS download (JavaFX's classes live in platform-classifier jars) and the single-jar promise is worth more.
Even *reaching* `slotEditors()` needs `javafx.scene.Node` on the classpath, because an editor written as a
lambda links its `(ValueContext)Node` method type when the list is built — so with no JavaFX the whole check
skips, saying so.

The half that is checked is the half that decides *which* editor a slot gets. The half that is not is seen
the first time anyone clicks the slot, which is what `botmaker plugin run` exists for.
