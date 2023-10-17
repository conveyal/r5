package com.conveyal.r5.analyst;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Envelope;

class WebMercatorExtentsTest {

    /**
     * Normal usage of Conveyal Analysis involves a feedback loop between Web Mercator extents and WGS84 extents.
     * This is because the API endpoint to create a new regional analysis requires extents in WGS84, but these are
     * always converted into Web Mercator to run the actual analysis. If someone wants to run another analysis with
     * the same dimensions, the UI must extract them from the database record or results of the previous analysis and
     * pass back through the WGS84 coordinate system.
     *
     * We originally made an ill-advised assumption that most WGS84 extents would not fall exactly on Mercator pixel
     * boundaries. This is sort of true for locations drawn from nature: the vast majority of real numbers are not
     * integers. But once we discretize these into Mercator pixels/cells and feed those values back into the conversion,
     * of course they are integers! In addition, there may be numerical instability in the conversion such that we
     * sometimes get integers and other times coordinates falling ever so slightly on either side of the pixel boundary.
     *
     * This test verifies that such repeated conversions are stable and do not yield an ever-increasing envelope size.
     * Ideally we should also test that this is the case when the non-integer WGS84 values are truncated by a few
     * digits (as when they are stored in a database or serialized over the wire).
     */
    @ParameterizedTest
    @ValueSource(ints = {9, 10, 11, 12})
    void wgsMercatorStability (int zoom) {
        Envelope wgsEnvelope = new Envelope();
        wgsEnvelope.expandToInclude(10.22222222, 45.111111);
        wgsEnvelope.expandBy(0.1);
        WebMercatorExtents webMercatorExtents = WebMercatorExtents.forWgsEnvelope(wgsEnvelope, zoom);

        for (int i = 0; i < 10; i++) {
            Assertions.assertTrue(webMercatorExtents.width > 20);
            Assertions.assertTrue(webMercatorExtents.height > 20);
            wgsEnvelope = webMercatorExtents.toWgsEnvelope();
            // Note the use of the trimmed envelope factory function, which should be used in the API.
            WebMercatorExtents webMercatorExtents2 = WebMercatorExtents.forTrimmedWgsEnvelope(wgsEnvelope, zoom);
            Assertions.assertEquals(webMercatorExtents2, webMercatorExtents);
            webMercatorExtents = webMercatorExtents2;
        }
    }

    /**
     * Check that a zero-size envelope (around a single point for example) will yield an extents object containing
     * one cell (rather than zero cells). Also check an envelope with a tiny nonzero envelope away from cell edges.
     */
    @ParameterizedTest
    @ValueSource(ints = {9, 10, 11, 12})
    void singleCellExtents (int zoom) {
        Envelope wgsEnvelope = new Envelope();

        wgsEnvelope.expandToInclude(10.22222222, 10.32222222);
        WebMercatorExtents webMercatorExtents = WebMercatorExtents.forWgsEnvelope(wgsEnvelope, zoom);
        Assertions.assertEquals(1, webMercatorExtents.width);
        Assertions.assertEquals(1, webMercatorExtents.height);

        wgsEnvelope.expandBy(0.00001);
        webMercatorExtents = WebMercatorExtents.forWgsEnvelope(wgsEnvelope, zoom);
        Assertions.assertEquals(1, webMercatorExtents.width);
        Assertions.assertEquals(1, webMercatorExtents.height);

        // Try taking these single-pixel extents through WGS84 for good measure.
        WebMercatorExtents wme2 = WebMercatorExtents.forTrimmedWgsEnvelope(webMercatorExtents.toWgsEnvelope(), zoom);
        Assertions.assertEquals(webMercatorExtents, wme2);
    }

}