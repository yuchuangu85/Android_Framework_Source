/*
 * Copyright (C) 2011 Google Inc.
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

package benchmarks.regression;

import com.google.caliper.Runner;
import com.google.caliper.SimpleBenchmark;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class AnnotatedElementBenchmark extends SimpleBenchmark {

    private Class<?> type;
    private Field field;
    private Method method;

    @Override protected void setUp() throws Exception {
        type = Type.class;
        field = Type.class.getField("field");
        method = Type.class.getMethod("method", String.class);
    }


    // get annotations by member type and method

    public void timeGetTypeAnnotations(int reps) {
        for (int i = 0; i < reps; i++) {
            type.getAnnotations();
        }
    }

    public void timeGetFieldAnnotations(int reps) {
        for (int i = 0; i < reps; i++) {
            field.getAnnotations();
        }
    }

    public void timeGetMethodAnnotations(int reps) {
        for (int i = 0; i < reps; i++) {
            method.getAnnotations();
        }
    }

    public void timeGetParameterAnnotations(int reps) {
        for (int i = 0; i < reps; i++) {
            method.getParameterAnnotations();
        }
    }

    public void timeGetTypeAnnotation(int reps) {
        for (int i = 0; i < reps; i++) {
            type.getAnnotation(Marker.class);
        }
    }

    public void timeGetFieldAnnotation(int reps) {
        for (int i = 0; i < reps; i++) {
            field.getAnnotation(Marker.class);
        }
    }

    public void timeGetMethodAnnotation(int reps) {
        for (int i = 0; i < reps; i++) {
            method.getAnnotation(Marker.class);
        }
    }

    public void timeIsTypeAnnotationPresent(int reps) {
        for (int i = 0; i < reps; i++) {
            type.isAnnotationPresent(Marker.class);
        }
    }

    public void timeIsFieldAnnotationPresent(int reps) {
        for (int i = 0; i < reps; i++) {
            field.isAnnotationPresent(Marker.class);
        }
    }

    public void timeIsMethodAnnotationPresent(int reps) {
        for (int i = 0; i < reps; i++) {
            method.isAnnotationPresent(Marker.class);
        }
    }

    // get annotations by result size

    public void timeGetAllReturnsLargeAnnotation(int reps) {
        for (int i = 0; i < reps; i++) {
            HasLargeAnnotation.class.getAnnotations();
        }
    }

    public void timeGetAllReturnsSmallAnnotation(int reps) {
        for (int i = 0; i < reps; i++) {
            HasSmallAnnotation.class.getAnnotations();
        }
    }

    public void timeGetAllReturnsMarkerAnnotation(int reps) {
        for (int i = 0; i < reps; i++) {
            HasMarkerAnnotation.class.getAnnotations();
        }
    }

    public void timeGetAllReturnsNoAnnotation(int reps) {
        for (int i = 0; i < reps; i++) {
            HasNoAnnotations.class.getAnnotations();
        }
    }

    public void timeGetAllReturnsThreeAnnotations(int reps) {
        for (int i = 0; i < reps; i++) {
            HasThreeAnnotations.class.getAnnotations();
        }
    }


    // get annotations with inheritance

    public void timeGetAnnotationsOnSubclass(int reps) {
        for (int i = 0; i < reps; i++) {
            ExtendsHasThreeAnnotations.class.getAnnotations();
        }
    }

    public void timeGetDeclaredAnnotationsOnSubclass(int reps) {
        for (int i = 0; i < reps; i++) {
            ExtendsHasThreeAnnotations.class.getAnnotations();
        }
    }


    // the annotated elements

    @Marker
    public class Type {
        @Marker public String field;
        @Marker public void method(@Marker String parameter) {}
    }

    @Large(a = "on class", b = {"A", "B", "C" },
            c = @Small(e="E1", f=1695938256, g=7264081114510713000L),
            d = { @Small(e="E2", f=1695938256, g=7264081114510713000L) })
    public class HasLargeAnnotation {}

    @Small(e="E1", f=1695938256, g=7264081114510713000L)
    public class HasSmallAnnotation {}

    @Marker
    public class HasMarkerAnnotation {}

    public class HasNoAnnotations {}

    @Large(a = "on class", b = {"A", "B", "C" },
            c = @Small(e="E1", f=1695938256, g=7264081114510713000L),
            d = { @Small(e="E2", f=1695938256, g=7264081114510713000L) })
    @Small(e="E1", f=1695938256, g=7264081114510713000L)
    @Marker
    public class HasThreeAnnotations {}

    public class ExtendsHasThreeAnnotations {}


    // the annotations

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker {}

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Large {
        String a() default "";
        String[] b() default {};
        Small c() default @Small;
        Small[] d() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Small {
        String e() default "";
        int f() default 0;
        long g() default 0L;
    }

    public static void main(String[] args) throws Exception {
        Runner.main(AnnotatedElementBenchmark.class, args);
    }
}
