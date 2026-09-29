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

package gov.nasa.worldwind.util;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

@RunWith(JUnit4.class)
public class EGM2008Test
{
    private static final double DELTA = 1e-4;

    /** Value written to the row markers of the synthetic data file. Any interpolation that reads a marker is off. */
    private static final float MARKER = 1.0e6f;

    /** Number of latitude rows written to the synthetic data file. */
    private static final int N_SYNTHETIC_ROWS = 48;

    /**
     * Path to the full EGM2008 2.5 minute grid, which is not included in the SDK due to its size. Tests that require
     * it are skipped when it is not available. See {@link EGM2008}.
     */
    private static final String EGM2008_DATA_PATH = System.getenv("EGM2008_DATA") != null
        ? System.getenv("EGM2008_DATA") : "config/egm2008_25.dat";

    private File syntheticFile;

    @Before
    public void setUp() throws IOException
    {
        this.syntheticFile = File.createTempFile("egm2008-synthetic", ".dat");
        this.syntheticFile.deleteOnExit();

        ByteBuffer row = ByteBuffer.allocate(EGM2008.N_LAT_ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try (FileOutputStream out = new FileOutputStream(this.syntheticFile))
        {
            for (int r = 0; r < N_SYNTHETIC_ROWS; r++)
            {
                row.clear();
                row.putFloat(MARKER);
                for (int c = 0; c < EGM2008.N_DATA_COLS; c++)
                {
                    row.putFloat(syntheticValue(r, c));
                }
                row.putFloat(MARKER);
                out.write(row.array());
            }
        }
    }

    @After
    public void tearDown()
    {
        //noinspection ResultOfMethodCallIgnored
        this.syntheticFile.delete();
    }

    /** Offsets vary smoothly with the row and column, with a discontinuity between the last and first columns. */
    private static float syntheticValue(int row, int col)
    {
        return 10f + row + col * 0.001f;
    }

    private static double lonForColumnFraction(int col, double fraction)
    {
        double lon360 = (col + fraction) * EGM2008.GRID_RESOLUTION;
        return lon360 >= 180 ? lon360 - 360 : lon360;
    }

    private static double latForRowFraction(int row, double fraction)
    {
        return 90 - (row + fraction) * EGM2008.GRID_RESOLUTION;
    }

    @Test
    public void testGetLonCol_StaysWithinDataColumns()
    {
        EGM2008 egm = new EGM2008(this.syntheticFile.getAbsolutePath());

        assertEquals(0, egm.getLonCol(0));
        assertEquals(EGM2008.N_DATA_COLS - 1, egm.getLonCol(-0.01));
        assertEquals(EGM2008.N_DATA_COLS / 2, egm.getLonCol(180));
        assertEquals(EGM2008.N_DATA_COLS / 2, egm.getLonCol(-180));
        assertEquals(0, egm.getLonCol(360));
        int col = egm.getLonCol(-1e-15);
        assertTrue("Column " + col + " outside data columns", col >= 0 && col < EGM2008.N_DATA_COLS);
    }

    @Test
    public void testGetOffset_InteriorInterpolation() throws IOException
    {
        EGM2008 egm = new EGM2008(this.syntheticFile.getAbsolutePath());

        int row = 10;
        int col = 100;
        double lat = latForRowFraction(row, 0.25);
        double lon = lonForColumnFraction(col, 0.5);
        double expected = 0.75 * (0.5 * syntheticValue(row, col) + 0.5 * syntheticValue(row, col + 1))
            + 0.25 * (0.5 * syntheticValue(row + 1, col) + 0.5 * syntheticValue(row + 1, col + 1));

        assertEquals(expected, egm.getOffset(lat, lon), DELTA);
    }

    @Test
    public void testGetOffset_AntimeridianWrapsToFirstColumn() throws IOException
    {
        EGM2008 egm = new EGM2008(this.syntheticFile.getAbsolutePath());

        int row = 20;
        int lastCol = EGM2008.N_DATA_COLS - 1;
        double lat = latForRowFraction(row, 0.5);
        double lon = lonForColumnFraction(lastCol, 0.5);
        assertEquals(lastCol, egm.getLonCol(lon));

        double expected = 0.5 * (0.5 * syntheticValue(row, lastCol) + 0.5 * syntheticValue(row, 0))
            + 0.5 * (0.5 * syntheticValue(row + 1, lastCol) + 0.5 * syntheticValue(row + 1, 0));

        assertEquals(expected, egm.getOffset(lat, lon), DELTA);
    }

    @Test
    public void testGetOffset_LongitudeJustBelowZeroIsNotAMarker() throws IOException
    {
        EGM2008 egm = new EGM2008(this.syntheticFile.getAbsolutePath());

        int row = 5;
        double lat = latForRowFraction(row, 0);
        for (double lon : new double[] {-1e-15, -1e-9, -0.0001, -0.01})
        {
            float offset = egm.getOffset(lat, lon);
            float min = Math.min(syntheticValue(row, 0), syntheticValue(row, EGM2008.N_DATA_COLS - 1));
            float max = Math.max(syntheticValue(row + 1, 0), syntheticValue(row + 1, EGM2008.N_DATA_COLS - 1));
            assertTrue("Offset " + offset + " at lon " + lon + " outside [" + min + ", " + max + "]",
                offset >= min - DELTA && offset <= max + DELTA);
        }
    }

    @Test
    public void testGetOffset_ConcurrentCallsAreConsistent() throws Exception
    {
        List<double[]> locations = new ArrayList<>();
        Random random = new Random(2008);
        for (int i = 0; i < 2000; i++)
        {
            int row = random.nextInt(N_SYNTHETIC_ROWS - 2);
            int col = random.nextInt(EGM2008.N_DATA_COLS);
            locations.add(new double[] {latForRowFraction(row, random.nextDouble()),
                lonForColumnFraction(col, random.nextDouble())});
        }

        EGM2008 reference = new EGM2008(this.syntheticFile.getAbsolutePath());
        float[] expected = new float[locations.size()];
        for (int i = 0; i < expected.length; i++)
        {
            expected[i] = reference.getOffset(locations.get(i)[0], locations.get(i)[1]);
        }

        assertConcurrentOffsetsMatch(new EGM2008(this.syntheticFile.getAbsolutePath()), locations, expected);
    }

    private static void assertConcurrentOffsetsMatch(final EGM2008 egm, final List<double[]> locations,
        final float[] expected) throws Exception
    {
        int nThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);
        try
        {
            final CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < nThreads; t++)
            {
                final int offset = t;
                futures.add(executor.submit(() ->
                {
                    start.await();
                    for (int n = 0; n < locations.size(); n++)
                    {
                        int i = (n + offset * 97) % locations.size();
                        double[] ll = locations.get(i);
                        assertEquals(expected[i], egm.getOffset(ll[0], ll[1]), 0f);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures)
            {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    /** Regression values for the coordinates used by the EGM2008Offsets example, computed from the full data set. */
    @Test
    public void testGetOffset_RealData_ExampleCoordinates() throws IOException
    {
        EGM2008 egm = new EGM2008(EGM2008_DATA_PATH);
        Assume.assumeTrue(egm.isEGMDataAvailable());

        assertEquals(-28.665308, egm.getOffset(47, -94), DELTA);
        assertEquals(-26.150883, egm.getOffset(37, -119), DELTA);
        assertEquals(-28.671270, egm.getOffset(47.02, -94.02), DELTA);
        assertEquals(-29.258514, egm.getOffset(36, -117), DELTA);
        assertEquals(-29.180916, egm.getOffset(36, -117.016667), DELTA);
        assertEquals(16.178350, egm.getOffset(89, 0), DELTA);
        assertEquals(-5.006723, egm.getOffset(-80, 0), DELTA);
        assertEquals(-30.149969, egm.getOffset(-90, 0), DELTA);
    }

    @Test
    public void testGetOffset_RealData_Antimeridian() throws IOException
    {
        EGM2008 egm = new EGM2008(EGM2008_DATA_PATH);
        Assume.assumeTrue(egm.isEGMDataAvailable());

        for (double lat : new double[] {0, 45, -45})
        {
            float[][] rows = egm.getLatRows(egm.getLatRow(lat));
            int first = 1;
            int last = EGM2008.N_LONGITUDE_COLS - 2;
            float min = Math.min(Math.min(rows[0][first], rows[0][last]), Math.min(rows[1][first], rows[1][last]));
            float max = Math.max(Math.max(rows[0][first], rows[0][last]), Math.max(rows[1][first], rows[1][last]));
            for (double lon : new double[] {-1e-9, -0.0001, -0.01, -0.03})
            {
                float offset = egm.getOffset(lat, lon);
                assertTrue("Offset " + offset + " at " + lat + "," + lon + " outside [" + min + ", " + max + "]",
                    offset >= min - DELTA && offset <= max + DELTA);
            }
        }
    }

    @Test
    public void testGetOffset_RealData_ConcurrentCallsAreConsistent() throws Exception
    {
        EGM2008 reference = new EGM2008(EGM2008_DATA_PATH);
        Assume.assumeTrue(reference.isEGMDataAvailable());

        double[] coords = {47, -94, 37, -119, 47.02, -94.02, 36, -117, 36, -117.041666666667, 89, 0, -80, 0, -90, 0,
            0, -0.01, 45, -0.01, -45, -0.03, 10, 179.99, 10, -179.99};
        List<double[]> locations = new ArrayList<>();
        for (int i = 0; i < coords.length; i += 2)
        {
            locations.add(new double[] {coords[i], coords[i + 1]});
        }
        float[] expected = new float[locations.size()];
        for (int i = 0; i < expected.length; i++)
        {
            expected[i] = reference.getOffset(locations.get(i)[0], locations.get(i)[1]);
        }

        for (int attempt = 0; attempt < 5; attempt++)
        {
            assertConcurrentOffsetsMatch(new EGM2008(EGM2008_DATA_PATH), locations, expected);
        }
    }
}
