/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package benchmarks;

import dalvik.system.VMDebug;

public class GetInstancesOfClassesBenchmark {
    private static class ObjectTree {
        ObjectTree left;
        ObjectTree right;

        public ObjectTree(int depth) {
            if (depth > 1) {
                left = new ObjectTree(depth - 1);
                right = new ObjectTree(depth - 1);
            }
        }
    }

    // 2^19 = 524288
    private static ObjectTree tree = new ObjectTree(19);

    public void timeGetInstancesOf1Class(int reps) {
        Class[] classes = new Class[]{
            Integer.class
        };
        for (int rep = 0; rep < reps; ++rep) {
            VMDebug.getInstancesOfClasses(classes, true);
        }
    }

    public void timeGetInstancesOf2Classes(int reps) {
        Class[] classes = new Class[]{
            Integer.class,
            Long.class
        };
        for (int rep = 0; rep < reps; ++rep) {
            VMDebug.getInstancesOfClasses(classes, true);
        }
    }

    public void timeGetInstancesOf4Classes(int reps) {
        Class[] classes = new Class[]{
            Integer.class,
            Long.class,
            Float.class,
            Double.class
        };
        for (int rep = 0; rep < reps; ++rep) {
            VMDebug.getInstancesOfClasses(classes, true);
        }
    }
}
