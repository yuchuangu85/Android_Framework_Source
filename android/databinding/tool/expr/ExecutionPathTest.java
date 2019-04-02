/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.databinding.tool.expr;


import android.databinding.tool.MockLayoutBinder;
import android.databinding.tool.reflection.java.JavaAnalyzer;
import android.databinding.tool.solver.ExecutionPath;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(Parameterized.class)
public class ExecutionPathTest {

    private final String mExpression;

    public ExecutionPathTest(String expression) {
        mExpression = expression;
    }


    @Parameterized.Parameters
    public static List<String> expressions() {
        return Arrays.asList("a.b(3/2)",
                "a ? (a ? b : c) : d",
                "a ? (b ? d : f) : g",
                "5 + 4 / 3 + 2 + 7 * 8",
                "a ? b : c");
    }

    @Before
    public void setUp() throws Exception {
        JavaAnalyzer.initForTests();
    }

    @Test
    public void simpleExpr() {
        MockLayoutBinder lb = new MockLayoutBinder();
        ExprModel model = lb.getModel();
        Expr parsed = lb.parse(mExpression, null, null);
        List<ExecutionPath> paths = new ArrayList<ExecutionPath>();
        ExecutionPath root = ExecutionPath.createRoot();
        paths.add(root);
        List<ExecutionPath> result = parsed.toExecutionPath(paths);
        StringBuilder sb = new StringBuilder();
        root.debug(sb, 0);
        sb.toString();
    }
}
