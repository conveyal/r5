package com.conveyal.r5.common;

/**
 * A very fast standalone implementation of Marsaglia, G. “Xorshift RNGs.” Journal of Statistical Software, 2003.
 * Optimized for use when generating many random numbers in the same range.
 * @author mattwigway
 */
public class Xorshift {
    private final int bitmask;
    private final int max;

    /** state of the generator */
    private int x, y, z, w;

    public Xorshift (int max) {
        this.max = max;
        int mask = 0;

        // figure out the minimum number of random bits we need
        int bits = 0;
        while (mask < max) mask = (mask << 1) | 0xf;

        bitmask = mask;
        x = (int) System.nanoTime();
        y = (int) (System.nanoTime() << 32);
        z = (int) System.currentTimeMillis();
        z = x ^ y;
    }

    public int nextInt () {
        int result;

        do {
            int temp = x ^ (x << 15);
            x = y;
            y = z;
            z = w;
            w = (w ^ (w >> 21)) ^ (temp ^ (temp >> 4));
            result = w & bitmask;
        } while (result >= max);

        return result;
    }

    public static void main (String... args) throws Exception {
        int max = Integer.parseInt(args[0]);
        int n = Integer.parseInt(args[1]);

        Xorshift xorshift = new Xorshift(max);

        Thread.sleep(1000);

        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            xorshift.nextInt();
        }
        long total = System.nanoTime() - start;

        System.out.println(String.format("Generating %d integers between 0 and %d took %.3f seconds", n, max, total / 1e6));
        //System.out.println(IntStream.of(result).mapToObj(Integer::toString).collect(Collectors.joining(",")));
    }
}
