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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

import static org.junit.Assert.*;

/** Verifies that KMZ entries whose names escape the extraction directory are never written to disk. */
@RunWith(JUnit4.class)
public class KMZZipSlipTest
{
    private static final String KML = "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document/></kml>";
    private static final String ICON_ENTRY = "files/icon.png";

    private String marker;
    private List<File> createdFiles = new ArrayList<File>();

    @Before
    public void setUp()
    {
        this.marker = "wwj-zipslip-" + UUID.randomUUID();
    }

    @After
    public void tearDown()
    {
        for (File file : this.createdFiles)
        {
            file.delete();
        }
    }

    @Test
    public void testResolveFileInDirectoryRejectsEscapingPaths() throws IOException
    {
        File root = WWIO.makeTempDir();
        this.createdFiles.add(root);

        assertNull(WWIO.resolveFileInDirectory(root, "../evil.txt"));
        assertNull(WWIO.resolveFileInDirectory(root, "a/../../evil.txt"));
        assertNull(WWIO.resolveFileInDirectory(root, "a/../b.txt"));
        assertNull(WWIO.resolveFileInDirectory(root, "..\\..\\evil.txt"));
        assertNull(WWIO.resolveFileInDirectory(root, "/etc/evil.txt"));
        assertNull(WWIO.resolveFileInDirectory(root, "\\evil.txt"));
        assertNull(WWIO.resolveFileInDirectory(root, "C:\\evil.txt"));
        assertNull(WWIO.resolveFileInDirectory(root, "c:evil.txt"));
        assertNull(WWIO.resolveFileInDirectory(root, ""));
        assertNull(WWIO.resolveFileInDirectory(root, "."));

        File ok = WWIO.resolveFileInDirectory(root, "files/./icon.png");
        assertNotNull(ok);
        assertEquals(new File(root.getCanonicalFile(), "files" + File.separator + "icon.png"), ok);
    }

    @Test
    public void testKMZInputStreamSkipsTraversalEntries() throws IOException
    {
        byte[] kmz = this.makeKMZ(true);

        KMZInputStream kmzStream = new KMZInputStream(new ByteArrayInputStream(kmz));
        InputStream kmlStream = kmzStream.getKMLStream();
        assertNotNull("KML entry not found", kmlStream);
        assertEquals(KML, new String(WWIO.readStreamToBuffer(kmlStream).array(), StandardCharsets.UTF_8).trim());
        kmlStream.close();

        String iconPath = kmzStream.getSupportFilePath(ICON_ENTRY);
        assertNotNull("Legitimate support file not extracted", iconPath);
        assertTrue(new File(iconPath).getCanonicalPath().startsWith(kmzStream.tempDir.getCanonicalPath()));
        this.createdFiles.add(new File(iconPath));

        // Drain the remaining entries, including the traversal entries placed after the KML.
        assertNull(kmzStream.getSupportFilePath("does/not/exist"));

        this.assertNoEscapedFiles(kmzStream.tempDir);
        for (String name : this.traversalNames())
        {
            assertNull(kmzStream.getSupportFilePath(name));
        }
    }

    @Test
    public void testKMZFileRejectsTraversalEntries() throws IOException
    {
        File kmzFile = File.createTempFile("wwj-zipslip", ".kmz");
        this.createdFiles.add(kmzFile);
        WWIO.saveBuffer(java.nio.ByteBuffer.wrap(this.makeKMZ(false)), kmzFile);

        KMZFile kmz = new KMZFile(kmzFile);
        try
        {
            String iconPath = kmz.getSupportFilePath(ICON_ENTRY);
            assertNotNull("Legitimate support file not extracted", iconPath);
            this.createdFiles.add(new File(iconPath));

            for (String name : this.traversalNames())
            {
                assertNull("Traversal entry extracted: " + name, kmz.getSupportFilePath(name));
            }

            this.assertNoEscapedFiles(kmz.tempDir);
        }
        finally
        {
            kmz.getZipFile().close();
        }
    }

    private List<String> traversalNames()
    {
        List<String> names = new ArrayList<String>();
        names.add("../" + this.marker + "-1.txt");
        names.add("../../" + this.marker + "-2.txt");
        names.add("files/../../" + this.marker + "-3.txt");
        names.add("..\\" + this.marker + "-4.txt");
        names.add(new File(System.getProperty("java.io.tmpdir"), this.marker + "-5.txt").getAbsolutePath());
        return names;
    }

    private void assertNoEscapedFiles(File tempDir) throws IOException
    {
        assertNotNull(tempDir);
        File parent = tempDir.getCanonicalFile().getParentFile();
        File grandParent = parent.getParentFile();
        for (int i = 1; i <= 5; i++)
        {
            String fileName = this.marker + "-" + i + ".txt";
            File inParent = new File(parent, fileName);
            File inGrandParent = new File(grandParent, fileName);
            this.createdFiles.add(inParent);
            this.createdFiles.add(inGrandParent);
            assertFalse("Escaped file written: " + inParent, inParent.exists());
            assertFalse("Escaped file written: " + inGrandParent, inGrandParent.exists());
        }
    }

    private byte[] makeKMZ(boolean traversalBeforeKML) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        List<String> traversal = this.traversalNames();

        if (traversalBeforeKML)
        {
            for (String name : traversal.subList(0, 3))
            {
                this.putEntry(zip, name, "pwned");
            }
        }

        this.putEntry(zip, "doc.kml", KML);
        this.putEntry(zip, ICON_ENTRY, "icon");

        for (String name : traversalBeforeKML ? traversal.subList(3, traversal.size()) : traversal)
        {
            this.putEntry(zip, name, "pwned");
        }

        zip.close();
        return bytes.toByteArray();
    }

    private void putEntry(ZipOutputStream zip, String name, String content) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
