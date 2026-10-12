<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is the module java/java.hints (the Java hints) of the Apache NetBeans source tree, which is built. NetBeans modules build with Ant; one test runs with `ant -q -Dtest.type=unit -Dtest.class=<class> -Dtest.methods=<method> test-method` in this directory (results in build/test/unit/results). The whole test suite of the module takes over an hour: run single test classes.

A user reports: in this code the editor warns "the assigned value is never used" on the line `data = switch(o) {`, although `data` is passed to `g(data)` afterwards. The warning goes away when the `throw` in the default case is replaced by a value such as `"j"`.

```java
public class Warn {
    public void f() {
        String data = null;
        Object o = g(null);
        if (o != null) {
            data = switch(o) { // warn: the assigned value is never used
            case Integer _ -> "i";
            default -> throw new IllegalArgumentException();
            };
        }
        g(data);
    }
    Object g(Object o) {
        return 1;
    }
}
```

The tests `UnusedAssignmentOrBranchTest#testSwitchExpressionWithThrowingCase9399` and `#testSwitchStatementRulesWithThrowingCase9399` reproduce it. Find the cause and fix it in the module's code (not in the tests). The module's tests must pass.
