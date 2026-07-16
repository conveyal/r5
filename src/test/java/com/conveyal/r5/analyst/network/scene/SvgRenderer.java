package com.conveyal.r5.analyst.network.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Renders a Scene as a simple SVG diagram to help visualize or debug the network layout.
/// Junctions and stops are labeled, and ways are visually distinguished by type. The SVG unit is
/// one meter, with y is flipped so north is up. The output uses only inline styles, so the file
/// can be viewed in isolation. The diagram aims to represent the scene and its component objects,
/// not the OSM or GTFS or Network that would be rendered from it.
class SvgRenderer {

    private static final int PADDING_METERS = 60;

    /// Length of the scale bar drawn at the bottom left of the diagram.
    private static final int SCALE_BAR_METERS = 100;

    static String render (Scene scene) {
        Bounds b = computeBounds(scene);
        double width = b.maxX - b.minX + 2 * PADDING_METERS;
        double height = b.maxY - b.minY + 2 * PADDING_METERS;
        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %.0f %.0f\" " +
            "width=\"%.0f\" height=\"%.0f\" font-family=\"sans-serif\">\n", width, height, width, height));
        svg.append(String.format(Locale.ROOT,
            "<rect x=\"0\" y=\"0\" width=\"%.0f\" height=\"%.0f\" fill=\"white\"/>\n", width, height));

        for (ScenePolygon polygon : scene.polygons.values()) {
            StringBuilder pts = new StringBuilder();
            for (int i = 0; i < polygon.ringXY.length - 2; i += 2) {
                pts.append(String.format(Locale.ROOT, "%.1f,%.1f ",
                    b.toSvgX(polygon.ringXY[i]), b.toSvgY(polygon.ringXY[i + 1])));
            }
            svg.append(String.format(Locale.ROOT,
                "<polygon points=\"%s\" fill=\"#2563eb\" fill-opacity=\"0.10\" stroke=\"#2563eb\" " +
                "stroke-opacity=\"0.5\" stroke-width=\"1.5\" stroke-dasharray=\"8,6\"/>\n", pts.toString().trim()));
            // Label at the ring's first vertex, nudged inward.
            svg.append(text(b.toSvgX(polygon.ringXY[0]) + 6, b.toSvgY(polygon.ringXY[1]) - 6, polygon.id, "#2563eb", 12));
        }

        for (SceneWay way : scene.ways) {
            if (way.points.size() < 2) continue;
            StringBuilder pts = new StringBuilder();
            for (SceneWay.Point p : way.points) {
                pts.append(String.format(Locale.ROOT, "%.1f,%.1f ", b.toSvgX(p.x), b.toSvgY(p.y)));
            }
            svg.append(String.format(Locale.ROOT,
                "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\"%s " +
                "stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n",
                pts.toString().trim(), color(way.preset), strokeWidth(way.preset), dashArray(way.preset)));
            double[] mid = midpointAndAngle(way.points);
            if (way.oneWay) {
                svg.append(String.format(Locale.ROOT,
                    "<g transform=\"translate(%.1f,%.1f) rotate(%.1f)\">" +
                    "<path d=\"M -6 -5 L 8 0 L -6 5 Z\" fill=\"%s\"/></g>\n",
                    b.toSvgX(mid[0]), b.toSvgY(mid[1]), -Math.toDegrees(mid[2]), color(way.preset)));
            }
            // Way names go below and right of the midpoint, while junction labels go above and
            // right. A name therefore does not collide with the label of a junction that happens
            // to sit at the way's midpoint, which is common for symmetric ways.
            if (way.name != null) {
                svg.append(text(b.toSvgX(mid[0]) + 6, b.toSvgY(mid[1]) + 18, way.name, "#666666", 11));
            }
        }

        for (SceneJunction junction : scene.junctions.values()) {
            svg.append(String.format(Locale.ROOT,
                "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"5\" fill=\"white\" stroke=\"#111111\" stroke-width=\"2\"/>\n",
                b.toSvgX(junction.x), b.toSvgY(junction.y)));
            svg.append(text(b.toSvgX(junction.x) + 8, b.toSvgY(junction.y) - 8, junction.name, "#111111", 12));
        }

        for (SceneStop stop : scene.stops) {
            svg.append(String.format(Locale.ROOT,
                "<rect x=\"%.1f\" y=\"%.1f\" width=\"12\" height=\"12\" fill=\"#dc2626\" " +
                "stroke=\"white\" stroke-width=\"2\"/>\n", b.toSvgX(stop.x) - 6, b.toSvgY(stop.y) - 6));
            svg.append(text(b.toSvgX(stop.x) + 10, b.toSvgY(stop.y) + 4, stop.id, "#dc2626", 12));
        }

        // Draw the scale bar at bottom left.
        double barY = height - 20;
        svg.append(String.format(Locale.ROOT,
            "<line x1=\"20\" y1=\"%.0f\" x2=\"%d\" y2=\"%.0f\" stroke=\"#111111\" stroke-width=\"2\"/>\n",
            barY, 20 + SCALE_BAR_METERS, barY));
        svg.append(text(30 + SCALE_BAR_METERS, barY + 4, SCALE_BAR_METERS + " m", "#111111", 12));

        svg.append("</svg>\n");
        return svg.toString();
    }

    private static String color (WayPreset preset) {
        return switch (preset) {
            case STREET -> "#6b7280";
            case SERVICE -> "#9ca3af";
            case MOTORWAY -> "#b45309";
            case RAMP -> "#f59e0b";
            case FOOTPATH -> "#15803d";
            case PEDESTRIAN -> "#7c3aed";
        };
    }

    private static double strokeWidth (WayPreset preset) {
        return switch (preset) {
            case STREET -> 6;
            case SERVICE -> 4;
            case MOTORWAY -> 10;
            case RAMP -> 5;
            case FOOTPATH -> 2.5;
            case PEDESTRIAN -> 4;
        };
    }

    private static String dashArray (WayPreset preset) {
        return switch (preset) {
            case FOOTPATH -> " stroke-dasharray=\"7,5\"";
            case PEDESTRIAN -> " stroke-dasharray=\"2,6\"";
            default -> "";
        };
    }

    private static String text (double x, double y, String content, String fill, int size) {
        return String.format(Locale.ROOT,
            "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"%d\">%s</text>\n",
            x, y, fill, size, escapeXml(content));
    }

    private static String escapeXml (String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /// Used to place one-way arrows and name labels.
    /// @return {x, y, angle} where (x, y) is the point at half the polyline's total length and
    ///  angle is the direction of the segment containing that point, in radians counterclockwise
    ///  from east.
    private static double[] midpointAndAngle (List<SceneWay.Point> points) {
        double total = 0;
        List<Double> lengths = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            double len = Math.hypot(points.get(i + 1).x - points.get(i).x, points.get(i + 1).y - points.get(i).y);
            lengths.add(len);
            total += len;
        }
        double remaining = total / 2;
        for (int i = 0; i < lengths.size(); i++) {
            if (remaining <= lengths.get(i) || i == lengths.size() - 1) {
                SceneWay.Point a = points.get(i);
                SceneWay.Point p = points.get(i + 1);
                double frac = lengths.get(i) == 0 ? 0 : remaining / lengths.get(i);
                double angle = Math.atan2(p.y - a.y, p.x - a.x);
                return new double[] {a.x + (p.x - a.x) * frac, a.y + (p.y - a.y) * frac, angle};
            }
            remaining -= lengths.get(i);
        }
        SceneWay.Point first = points.getFirst();
        return new double[] {first.x, first.y, 0};
    }

    /// The bounding box of all scene objects in meters.
    /// Has methods to convert meter coordinates to padded SVG coordinates with y increasing downward.
    private record Bounds (double minX, double minY, double maxX, double maxY) {

        double toSvgX (double x) {
            return x - minX + PADDING_METERS;
        }

        double toSvgY (double y) {
            return maxY - y + PADDING_METERS;
        }
    }

    private static Bounds computeBounds (Scene scene) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        List<double[]> allPoints = new ArrayList<>();
        for (SceneWay way : scene.ways) {
            for (SceneWay.Point p : way.points) allPoints.add(new double[] {p.x, p.y});
        }
        for (SceneJunction j : scene.junctions.values()) allPoints.add(new double[] {j.x, j.y});
        for (SceneStop s : scene.stops) allPoints.add(new double[] {s.x, s.y});
        for (ScenePolygon z : scene.polygons.values()) {
            for (int i = 0; i < z.ringXY.length; i += 2) allPoints.add(new double[] {z.ringXY[i], z.ringXY[i + 1]});
        }
        // An empty scene gets a canvas one scale bar in size, so the bar and its label still fit.
        if (allPoints.isEmpty()) return new Bounds(0, 0, SCALE_BAR_METERS, SCALE_BAR_METERS);
        for (double[] p : allPoints) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return new Bounds(minX, minY, maxX, maxY);
    }

}
