/*
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

package com.clearspring.analytics.stream.frequency;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import com.clearspring.analytics.stream.frequency.CountMinSketch.CMSMergeException;
import com.clearspring.analytics.TestUtils;

import org.apache.commons.lang3.RandomStringUtils;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CountMinSketchTest {

    @Test
    public void testSize() throws CMSMergeException {
        CountMinSketch sketch = new CountMinSketch(0.00001, 0.99999, 1);
        assertEquals(0, sketch.size(), 0);

        sketch.add(1, 11);
        sketch.add(2, 22);
        sketch.add(3, 33);

        long expectedSize = 11 + 22 + 33;
        assertEquals(expectedSize, sketch.size());
    }

    @Test
    public void testSizeCanStoreLong() throws CMSMergeException {
        double confidence = 0.999;
        double epsilon = 0.0001;
        int seed = 1;

        CountMinSketch sketch = new CountMinSketch(epsilon, confidence, seed);

        long freq1 = Integer.MAX_VALUE;
        long freq2 = 156;

        sketch.add(1, freq1);
        sketch.add(2, freq2);

        CountMinSketch newSketch = CountMinSketch.merge(sketch, sketch);

        long expectedSize = 2 * (freq1 + freq2);
        assertEquals(expectedSize, newSketch.size());
    }

    @Test
    public void testAccuracy() {
        int seed = 7364181;
        Random r = new Random(seed);
        int numItems = 1000000;
        int[] xs = new int[numItems];
        int maxScale = 20;
        for (int i = 0; i < numItems; i++) {
            int scale = r.nextInt(maxScale);
            xs[i] = r.nextInt(1 << scale);
        }

        double epsOfTotalCount = 0.0001;
        double confidence = 0.99;

        CountMinSketch sketch = new CountMinSketch(epsOfTotalCount, confidence, seed);
        for (int x : xs) {
            sketch.add(x, 1);
        }

        int[] actualFreq = new int[1 << maxScale];
        for (int x : xs) {
            actualFreq[x]++;
        }

        sketch = CountMinSketch.deserialize(CountMinSketch.serialize(sketch));

        int numErrors = 0;
        for (int i = 0; i < actualFreq.length; ++i) {
            double ratio = ((double) (sketch.estimateCount(i) - actualFreq[i])) / numItems;
            if (ratio > epsOfTotalCount) {
                numErrors++;
            }
        }
        double pCorrect = 1.0 - ((double) numErrors) / actualFreq.length;
        assertTrue("Confidence not reached: required " + confidence + ", reached " + pCorrect, pCorrect > confidence);
    }

    @Test
    public void testAccuracyStrings() {
        int seed = 7364181;
        Random r = new Random(seed);
        int numItems = 1000000;
        int absentItems = numItems * 10;
        String[] xs = new String[numItems];
        int maxScale = 20;
        for (int i = 0; i < numItems; i++) {
            int scale = r.nextInt(maxScale);
            xs[i] = RandomStringUtils.random(scale);
        }

        double epsOfTotalCount = 0.0001;
        double confidence = 0.99;

        CountMinSketch sketch = new CountMinSketch(epsOfTotalCount, confidence, seed);
        for (String x : xs) {
            sketch.add(x, 1);
        }

        Map<String, Long> actualFreq = new HashMap<String, Long>();
        for (String x : xs) {
            Long val = actualFreq.get(x);
            if (val == null) {
                actualFreq.put(x, 1L);
            } else {
                actualFreq.put(x, val + 1L);
            }
        }

        sketch = CountMinSketch.deserialize(CountMinSketch.serialize(sketch));

        int numErrors = 0;
        for (Map.Entry<String,Long> entry : actualFreq.entrySet()) {
            String key = entry.getKey();
            long count = entry.getValue();
            double ratio = ((double) (sketch.estimateCount(key) - count)) / numItems;
            if (ratio > epsOfTotalCount) {
                numErrors++;
            }
        }
        for (int i = 0; i < absentItems; i++) {
            int scale = r.nextInt(maxScale);
            String key = RandomStringUtils.random(scale);
            Long value = actualFreq.get(key);
            long count = (value == null) ? 0L : value;
            double ratio = ((double) (sketch.estimateCount(key) - count)) / numItems;
            if (ratio > epsOfTotalCount) {
                numErrors++;
            }
        }

        double pCorrect = 1.0 - ((double) numErrors) / (numItems + absentItems);
        System.out.println(pCorrect);
        assertTrue("Confidence not reached: required " + confidence + ", reached " + pCorrect, pCorrect > confidence);

        assertTrue("Confidence not reached: required " + confidence + ", reached " + pCorrect, pCorrect > confidence);
    }


    @Test
    public void merge() throws CMSMergeException {
        int numToMerge = 5;
        int cardinality = 1000000;

        double epsOfTotalCount = 0.0001;
        double confidence = 0.99;
        int seed = 7364181;

        int maxScale = 20;
        Random r = new Random();
        TreeSet<Integer> vals = new TreeSet<Integer>();

        CountMinSketch baseline = new CountMinSketch(epsOfTotalCount, confidence, seed);
        CountMinSketch[] sketchs = new CountMinSketch[numToMerge];
        for (int i = 0; i < numToMerge; i++) {
            sketchs[i] = new CountMinSketch(epsOfTotalCount, confidence, seed);
            for (int j = 0; j < cardinality; j++) {
                int scale = r.nextInt(maxScale);
                int val = r.nextInt(1 << scale);
                vals.add(val);
                sketchs[i].add(val, 1);
                baseline.add(val, 1);
            }
        }

        CountMinSketch merged = CountMinSketch.merge(sketchs);

        assertEquals(baseline.size(), merged.size());
        assertEquals(baseline.getConfidence(), merged.getConfidence(), baseline.getConfidence() / 100);
        assertEquals(baseline.getRelativeError(), merged.getRelativeError(), baseline.getRelativeError() / 100);
        for (int val : vals) {
            assertEquals(baseline.estimateCount(val), merged.estimateCount(val));
        }
    }

    @Test
    public void testMergeEmpty() throws CMSMergeException {
        assertNull(CountMinSketch.merge());
    }

    @Test(expected = CMSMergeException.class)
    public void testUncompatibleMerge() throws CMSMergeException {
        CountMinSketch cms1 = new CountMinSketch(1, 1, 0);
        CountMinSketch cms2 = new CountMinSketch(0.1, 0.1, 0);
        CountMinSketch.merge(cms1, cms2);
    }

    private static void checkCountMinSketchSerialization(CountMinSketch cms) throws IOException, ClassNotFoundException {
        byte[] bytes = TestUtils.serialize(cms);
        CountMinSketch serializedCms = (CountMinSketch)TestUtils.deserialize(bytes);

        assertEquals(cms.size, serializedCms.size);

        assertEquals(cms.eps, serializedCms.eps, 0);
        assertEquals(cms.confidence, serializedCms.confidence, 0);

        assertEquals(cms.width, serializedCms.width);
        assertEquals(cms.depth, serializedCms.depth);

        assertArrayEquals(cms.table, serializedCms.table);
        assertArrayEquals(cms.hashA, serializedCms.hashA);

        for (int i = 0; i < cms.depth; ++i) {
            assertEquals(cms.hashA[i], serializedCms.hashA[i]);
            for (int j = 0; j < cms.width; ++j) {
                assertEquals(cms.table[i][j], serializedCms.table[i][j]);
            }
        }
    }

    @Test
    public void testSerializationForDepthCms() throws IOException, ClassNotFoundException {
        checkCountMinSketchSerialization(new CountMinSketch(12, 2045, 1));
    }

    @Test
    public void testSerializationForConfidenceCms() throws IOException, ClassNotFoundException {
        checkCountMinSketchSerialization(new CountMinSketch(0.0001, 0.99999999999, 1));
    }
}
