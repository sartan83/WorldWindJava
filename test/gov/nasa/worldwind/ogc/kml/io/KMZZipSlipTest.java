/*
 * Copyright 2006-2009, 2017, 2020 United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 * 
 * The NASA World Wind Java (WWJ) platform is licensed under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 * 
 * NASA World Wind Java (WWJ) also contains the following 3rd party Open Source
 * software:
 * 
 *     Jackson Parser – Licensed under Apache 2.0
 *     GDAL – Licensed under MIT
 *     JOGL – Licensed under  Berkeley Software Distribution (BSD)
 *     Gluegen – Licensed under Berkeley Software Distribution (BSD)
 * 
 * A complete listing of 3rd Party software notices and licenses included in
 * NASA World Wind Java (WWJ)  can be found in the WorldWindJava-v2.2 3rd-party
 * notices and licenses PDF found in code directory.
 */

package gov.nasa.worldwind.ogc.kml.io;

import gov.nasa.worldwind.util.WWIO;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.Assert.*;

/**
 * Verifies that KMZ entries whose names escape the extraction directory ("Zip Slip") are never written to disk by
 * {@link KMZFile} or {@link KMZInputStream}, while legitimate relative entries are still extracted.
 */
@RunWith(JUnit4.class)
public class KMZZipSlipTest
{
    private static final String KML_ENTRY = "doc.kml";
    private static final String GOOD_ENTRY = "files/icon.png";
    private static final byte[] PAYLOAD = "PWNED".getBytes();

    private File workDir;
    private File kmzFile;
    private List<String> maliciousEntries;
    private List<File> escapeTargets;

    @Before
    public void setUp() throws IOException
    {
        // The temp dir created by KMZFile/KMZInputStream lives directly in java.io.tmpdir, so "../name" lands in
        // java.io.tmpdir. Use unique names so the assertions cannot be fooled by pre-existing files.
        String unique = "wwj-zipslip-" + UUID.randomUUID();
        File sysTmp = new File(System.getProperty("java.io.tmpdir"));

        this.maliciousEntries = Arrays.asList(
            "../" + unique + "-a.txt",
            "files/../../" + unique + "-b.txt",
            "..\\" + unique + "-c.txt",
            "../" + unique + "-dir/d.txt");
        this.escapeTargets = Arrays.asList(
            new File(sysTmp, unique + "-a.txt"),
            new File(sysTmp, unique + "-b.txt"),
            new File(sysTmp, unique + "-c.txt"),
            new File(sysTmp, unique + "-dir"));

        this.workDir = WWIO.makeTempDir();
        this.kmzFile = new File(this.workDir, "evil.kmz");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(this.kmzFile)))
        {
            for (String name : this.maliciousEntries)
            {
                putEntry(zos, name, PAYLOAD);
            }
            putEntry(zos, GOOD_ENTRY, new byte[] {1, 2, 3});
            putEntry(zos, KML_ENTRY, ("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Placemark><Style><IconStyle>"
                + "<Icon><href>" + this.maliciousEntries.get(0) + "</href></Icon></IconStyle></Style>"
                + "<Point><coordinates>0,0</coordinates></Point></Placemark></kml>").getBytes());
        }
    }

    @After
    public void tearDown()
    {
        for (File f : this.escapeTargets)
        {
            new File(f, "d.txt").delete();
            f.delete();
        }
        this.kmzFile.delete();
        this.workDir.delete();
    }

    @Test
    public void testKMZFileRejectsEscapingEntries() throws IOException
    {
        KMZFile kmz = new KMZFile(this.kmzFile);
        try
        {
            for (String name : this.maliciousEntries)
            {
                assertNull("Escaping entry must not be extracted: " + name, kmz.getSupportFilePath(name));
            }
            this.assertNothingEscaped();

            String good = kmz.getSupportFilePath(GOOD_ENTRY);
            assertNotNull(good);
            assertInside(kmz.tempDir, new File(good));
        }
        finally
        {
            kmz.getZipFile().close();
        }
    }

    @Test
    public void testKMZInputStreamRejectsEscapingEntries() throws IOException
    {
        KMZInputStream kmz = new KMZInputStream(new FileInputStream(this.kmzFile));

        // getKMLStream copies every entry preceding the KML entry, so this exercises all malicious names.
        InputStream kml = kmz.getKMLStream();
        assertNotNull(kml);
        kml.close();
        this.assertNothingEscaped();

        for (String name : this.maliciousEntries)
        {
            assertNull("Escaping entry must not be extracted: " + name, kmz.getSupportFilePath(name));
        }

        String good = kmz.getSupportFilePath(GOOD_ENTRY);
        assertNotNull(good);
        assertInside(kmz.tempDir, new File(good));
    }

    @Test
    public void testResolveContainedFile() throws IOException
    {
        File base = new File(this.workDir, "base");

        assertInside(base, WWIO.resolveContainedFile(base, "a.png"));
        assertInside(base, WWIO.resolveContainedFile(base, "files/a.png"));
        assertInside(base, WWIO.resolveContainedFile(base, "files/../a.png"));
        assertInside(base, WWIO.resolveContainedFile(base, "files\\a.png"));

        assertNull(WWIO.resolveContainedFile(base, ""));
        assertNull(WWIO.resolveContainedFile(base, "."));
        assertNull(WWIO.resolveContainedFile(base, "files/.."));
        assertNull(WWIO.resolveContainedFile(base, "../a.png"));
        assertNull(WWIO.resolveContainedFile(base, "files/../../a.png"));
        assertNull(WWIO.resolveContainedFile(base, "..\\..\\a.png"));
        assertNull(WWIO.resolveContainedFile(base, "/etc/passwd"));
        assertNull(WWIO.resolveContainedFile(base, "\\Windows\\win.ini"));
        assertNull(WWIO.resolveContainedFile(base, "C:\\Windows\\win.ini"));
        assertNull(WWIO.resolveContainedFile(base, "C:a.png"));
        assertNull(WWIO.resolveContainedFile(base, "a.png\0.txt"));
    }

    private void assertNothingEscaped()
    {
        for (File f : this.escapeTargets)
        {
            assertFalse("File written outside the temp dir: " + f, f.exists());
        }
    }

    private static void assertInside(File dir, File file) throws IOException
    {
        assertNotNull(file);
        String dirPath = dir.getCanonicalPath() + File.separator;
        assertTrue(file + " is not inside " + dir, file.getCanonicalPath().startsWith(dirPath));
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] data) throws IOException
    {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }
}
