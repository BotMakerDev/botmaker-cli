# Why the stub contexts are written here

`botmaker-plugin-toolkit.testing.TestContexts` does the same job. It is not used, for the same reason
`botmaker-studio` may not depend on the toolkit: **the toolkit is a plugin's dependency, resolved onto the
plugin's own classloader so that two plugins may hold two versions of it.** A host that resolves one version
onto its own classpath takes that away. Forty lines of `StubContexts` is the price of the rule, and it is
cheap.

Every `StudioServices` method on the stub throws, deliberately: a predicate is asked *which slot is this*,
and it has the type and the call site to answer with. One that reaches for the theme is doing something a
headless host cannot support, and the validator reports that as the editor's failure rather than its own.
