/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8146486
 * @summary Fail to create a MR modular JAR with a versioned entry in
 *          base-versioned empty package
 * @modules jdk.compiler
 *          jdk.jartool
 * @library /lib/testlibrary
 * @build jdk.testlibrary.FileUtils
 * @run testng ConcealedPackage
 */

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.testlibrary.FileUtils;

public class ConcealedPackage {
    private static final ToolProvider JAR_TOOL = ToolProvider.findFirst("jar")
           .orElseThrow(() -> new RuntimeException("jar tool not found"));
    private static final ToolProvider JAVAC_TOOL = ToolProvider.findFirst("javac")
            .orElseThrow(() -> new RuntimeException("javac tool not found"));
    private final String linesep = System.lineSeparator();
    private final Path testsrc;
    private final Path userdir;
    private final ByteArrayOutputStream outbytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outbytes, true);
    private final ByteArrayOutputStream errbytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errbytes, true);

    public ConcealedPackage() throws IOException {
        testsrc = Paths.get(System.getProperty("test.src"));
        userdir = Paths.get(System.getProperty("user.dir", "."));

        // compile the classes directory
        Path source = testsrc.resolve("src").resolve("classes");
        Path destination = Paths.get("classes");
        javac(source, destination);

        // compile the mr9 directory including module-info.java
        source = testsrc.resolve("src").resolve("mr9");
        destination = Paths.get("mr9");
        javac(source, destination);

        // move module-info.class for later use
        Files.move(destination.resolve("module-info.class"),
                Paths.get("module-info.class"));
    }

    private void javac(Path source, Path destination) throws IOException {
        String[] args = Stream.concat(
                Stream.of("-d", destination.toString()),
                Files.walk(source)
                        .map(Path::toString)
                        .filter(s -> s.endsWith(".java"))
        ).toArray(String[]::new);
        JAVAC_TOOL.run(System.out, System.err, args);
    }

    private int jar(String cmd) {
        outbytes.reset();
        errbytes.reset();
        return JAR_TOOL.run(out, err, cmd.split(" +"));
    }

    @AfterClass
    public void cleanup() throws IOException {
        Files.walk(userdir, 1)
                .filter(p -> !p.equals(userdir))
                .forEach(p -> {
                    try {
                        if (Files.isDirectory(p)) {
                            FileUtils.deleteFileTreeWithRetry(p);
                        } else {
                            FileUtils.deleteFileIfExistsWithRetry(p);
                        }
                    } catch (IOException x) {
                        throw new UncheckedIOException(x);
                    }
                });
    }

    // updates a valid multi-release jar with a new public class in
    // versioned section and fails
    @Test
    public void test1() {
        // successful build of multi-release jar
        int rc = jar("-cf mmr.jar -C classes . --release 9 -C mr9 p/Hi.class");
        Assert.assertEquals(rc, 0);

        jar("-tf mmr.jar");

        String s = new String(outbytes.toByteArray());
        Set<String> actual = Arrays.stream(s.split(linesep)).collect(Collectors.toSet());
        Set<String> expected = Set.of(
                "META-INF/",
                "META-INF/MANIFEST.MF",
                "p/",
                "p/Hi.class",
                "META-INF/versions/9/p/Hi.class"
        );
        Assert.assertEquals(actual, expected);

        // failed build because of new public class
        rc = jar("-uf mmr.jar --release 9 -C mr9 p/internal/Bar.class");
        Assert.assertEquals(rc, 1);

        s = new String(errbytes.toByteArray());
        Assert.assertTrue(Message.NOT_FOUND_IN_BASE_ENTRY.match(s, "p/internal/Bar.class"));
    }

    // updates a valid multi-release jar with a module-info class and new
    // concealed public class in versioned section and succeeds
    @Test
    public void test2() {
        // successful build of multi-release jar
        int rc = jar("-cf mmr.jar -C classes . --release 9 -C mr9 p/Hi.class");
        Assert.assertEquals(rc, 0);

        // successful build because of module-info and new public class
        rc = jar("-uf mmr.jar module-info.class --release 9 -C mr9 p/internal/Bar.class");
        Assert.assertEquals(rc, 0);

        String s = new String(errbytes.toByteArray());
        Assert.assertTrue(Message.NEW_CONCEALED_PACKAGE_WARNING.match(s, "p/internal/Bar.class"));

        jar("-tf mmr.jar");

        s = new String(outbytes.toByteArray());
        Set<String> actual = Arrays.stream(s.split(linesep)).collect(Collectors.toSet());
        Set<String> expected = Set.of(
                "META-INF/",
                "META-INF/MANIFEST.MF",
                "p/",
                "p/Hi.class",
                "META-INF/versions/9/p/Hi.class",
                "META-INF/versions/9/p/internal/Bar.class",
                "module-info.class"
        );
        Assert.assertEquals(actual, expected);
    }

    // jar tool fails building mmr.jar because of new public class
    @Test
    public void test3() {
        int rc = jar("-cf mmr.jar -C classes . --release 9 -C mr9 .");
        Assert.assertEquals(rc, 1);

        String s = new String(errbytes.toByteArray());
        Assert.assertTrue(Message.NOT_FOUND_IN_BASE_ENTRY.match(s, "p/internal/Bar.class"));
    }

    // jar tool succeeds building mmr.jar because of concealed package
    @Test
    public void test4() {
        int rc = jar("-cf mmr.jar module-info.class -C classes . " +
                "--release 9 module-info.class -C mr9 .");
        Assert.assertEquals(rc, 0);

        String s = new String(errbytes.toByteArray());
        Assert.assertTrue(Message.NEW_CONCEALED_PACKAGE_WARNING.match(s, "p/internal/Bar.class"));

        jar("-tf mmr.jar");

        s = new String(outbytes.toByteArray());
        Set<String> actual = Arrays.stream(s.split(linesep)).collect(Collectors.toSet());
        Set<String> expected = Set.of(
                "META-INF/",
                "META-INF/MANIFEST.MF",
                "module-info.class",
                "META-INF/versions/9/module-info.class",
                "p/",
                "p/Hi.class",
                "META-INF/versions/9/p/",
                "META-INF/versions/9/p/Hi.class",
                "META-INF/versions/9/p/internal/",
                "META-INF/versions/9/p/internal/Bar.class"
        );
        Assert.assertEquals(actual, expected);
    }

    // jar tool does two updates, no exported packages, all concealed
    @Test
    public void test5() throws IOException {
        // compile the mr10 directory
        Path source = testsrc.resolve("src").resolve("mr10");
        Path destination = Paths.get("mr10");
        javac(source, destination);

        // create a directory for this tests special files
        Files.createDirectory(Paths.get("test5"));

        // create an empty module-info.java
        String hi = "module hi {" + linesep + "}" + linesep;
        Path modinfo = Paths.get("test5", "module-info.java");
        Files.write(modinfo, hi.getBytes());

        // and compile it
        javac(modinfo, Paths.get("test5"));

        int rc = jar("--create --file mr.jar -C classes .");
        Assert.assertEquals(rc, 0);

        rc = jar("--update --file mr.jar -C test5 module-info.class"
                + " --release 9 -C mr9 .");
        Assert.assertEquals(rc, 0);

        jar("tf mr.jar");

        String s = new String(outbytes.toByteArray());
        Set<String> actual = Arrays.stream(s.split(linesep)).collect(Collectors.toSet());
        Set<String> expected = Set.of(
                "META-INF/",
                "META-INF/MANIFEST.MF",
                "p/",
                "p/Hi.class",
                "META-INF/versions/9/p/",
                "META-INF/versions/9/p/Hi.class",
                "META-INF/versions/9/p/internal/",
                "META-INF/versions/9/p/internal/Bar.class",
                "module-info.class"
        );
        Assert.assertEquals(actual, expected);

        jar("-d --file mr.jar");

        s = new String(outbytes.toByteArray());
        actual = Arrays.stream(s.split(linesep))
                .filter(l -> (l.length() > 0))
                .map(l -> l.trim())
                .collect(Collectors.toSet());
        expected = Set.of(
                "hi",
                "requires mandated java.base",
                "contains p",
                "contains p.internal"
        );
        Assert.assertEquals(actual, expected);

        rc = jar("--update --file mr.jar --release 10 -C mr10 .");
        Assert.assertEquals(rc, 0);

        jar("tf mr.jar");

        s = new String(outbytes.toByteArray());
        actual = Arrays.stream(s.split(linesep)).collect(Collectors.toSet());
        expected = Set.of(
                "META-INF/",
                "META-INF/MANIFEST.MF",
                "p/",
                "p/Hi.class",
                "META-INF/versions/9/p/",
                "META-INF/versions/9/p/Hi.class",
                "META-INF/versions/9/p/internal/",
                "META-INF/versions/9/p/internal/Bar.class",
                "META-INF/versions/10/p/",
                "META-INF/versions/10/p/internal/",
                "META-INF/versions/10/p/internal/bar/",
                "META-INF/versions/10/p/internal/bar/Gee.class",
                "module-info.class"
        );
        Assert.assertEquals(actual, expected);

        jar("-d --file mr.jar");

        s = new String(outbytes.toByteArray());
        actual = Arrays.stream(s.split(linesep))
                .filter(l -> (l.length() > 0))
                .map(l -> l.trim())
                .collect(Collectors.toSet());
        expected = Set.of(
                "hi",
                "requires mandated java.base",
                "contains p",
                "contains p.internal",
                "contains p.internal.bar"
        );
        Assert.assertEquals(actual, expected);
    }

    static enum Message {
        NOT_FOUND_IN_BASE_ENTRY(
          ", contains a new public class not found in base entries"
        ),
        NEW_CONCEALED_PACKAGE_WARNING(
            " is a public class" +
            " in a concealed package, placing this jar on the class path will result" +
            " in incompatible public interfaces"
        );

        final String msg;
        Message(String msg) {
            this.msg = msg;
        }

        /*
         * Test if the given output contains this message ignoring the line break.
         */
        boolean match(String output, String entry) {
            System.out.println("checking " + entry + msg);
            System.out.println(output);
            return Arrays.stream(output.split("\\R"))
                         .collect(Collectors.joining(" "))
                         .contains(entry + msg);
        }
    }
}
