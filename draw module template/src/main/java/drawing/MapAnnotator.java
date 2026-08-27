package drawing;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import VASSAL.build.AbstractConfigurable;
import VASSAL.build.Buildable;
import VASSAL.build.GameModule;
import VASSAL.build.module.GameComponent;
import VASSAL.build.module.Map;
import VASSAL.build.module.documentation.HelpFile;
import VASSAL.build.module.map.Drawable;
import VASSAL.build.module.PlayerRoster;
import VASSAL.command.Command;
import VASSAL.command.CommandEncoder;
import VASSAL.build.AutoConfigurable;
import VASSAL.configure.ColorConfigurer;
import VASSAL.configure.Configurer;
import VASSAL.configure.ConfigurerFactory;
import VASSAL.configure.FontConfigurer;
import VASSAL.configure.IconConfigurer;
import VASSAL.configure.NamedHotKeyConfigurer;
import VASSAL.tools.NamedKeyStroke;
import VASSAL.tools.imageop.ImageOp;
import VASSAL.tools.imageop.Op;

public class MapAnnotator extends AbstractConfigurable
        implements Drawable, GameComponent, CommandEncoder, MouseListener, MouseMotionListener {

    public static final String ID = "MapAnnotator";
    public static final String COMMAND_PREFIX = "ANNOTATE;";

    // Editor properties - Visuals
    private Color drawColor = Color.RED;
    private double drawWidth = 3.0;
    private int eraserRadius = 14;
    private Font font = new Font("SansSerif", Font.PLAIN, 20);
    private Color textColor = Color.RED;

    // Editor properties - Feature toggles
    private boolean drawingEnabled = true;
    private boolean textEnabled = true;
    private boolean shapesEnabled = true;

    // Editor properties - Per-side independent drawing
    private boolean independentSides = false;
    private boolean multilayerDrawing = false;
    private String sideSettingsEncoded = "";
    private final ArrayList<SideSetting> sideSettingsList = new ArrayList<>();

    // Editor properties - Multilayer drawing
    private final ArrayList<Layer> layers = new ArrayList<>();
    private String activeLayerId = null;
    private static final String NONE_LAYER_ID = "__none__";
    private transient boolean suppressComboListener = false;
    private transient PlayerRoster.SideChangeListener sideChangeListener;

    // Editor properties - UI & Hotkeys
    private String btnDrawText = "Draw", btnTextText = "Text", btnShapesText = "Shapes ▼", btnGumText = "Gum", btnClearText = "Clear";
    private NamedKeyStroke hkDraw, hkText, hkShapes, hkGum, hkClear;

    // Runtime state (ALWAYS MAP COORDINATES)
    private final ArrayList<SvgPath> paths = new ArrayList<>();
    private final ArrayList<TextItem> texts = new ArrayList<>();

    // UI State
    private enum Mode { OFF, DRAW, TEXT, GUM, SHAPE }
    private enum ShapeType { RECTANGLE, ELLIPSE, ARROW, CUSTOM }
    private Mode mode = Mode.OFF;
    private ShapeType currentShape = ShapeType.ARROW;
    private Map map;

    // Transient drawing state
    private transient int gumAppliedIdx = 0;
    private transient boolean dragging = false;
    private transient ArrayList<Point> inProgressPoints = null;
    private transient ArrayList<Point> eraserPath = null;
    private transient Point shapeStart = null;
    private transient Point cursorMap = null;
    private transient ArrayList<SvgPath> previewPaths = null;
    private transient ArrayList<TextItem> previewTexts = null;



    // Whether this annotator currently owns the map's mouseListenerStack slot.
    // We push ourselves onto the stack ONLY while a drawing mode is active,
    // so that normal piece selection / dragging keeps working while OFF.
    // See updateStackMembership() and the note in addTo().
    private transient boolean listenerOnStack = false;

    // Button icon image names (loaded from the module's image archive via VASSAL Op.load).
    // If set, the icon replaces the button text; if blank/missing the text label is used.
    private String btnDrawIcon, btnTextIcon, btnShapesIcon, btnGumIcon, btnClearIcon;

    // Custom designer-imported SVG shapes.
    // Encoded as:  Name1|SVGpathData1||Name2|SVGpathData2||...
    // SVG path data uses absolute commands: M, L, C, Z (e.g. "M 0 0 L 10 0 L 10 10 Z")
    private String customShapesEncoded = "";
    private final ArrayList<CustomShape> customShapesList = new ArrayList<>();
    private transient CustomShape selectedCustomShape = null;

    // Live text preview state (active while the edit dialog is open).
    // livePreviewEditingId, when non-null, hides the matching committed TextItem
    // so the preview doesn't double-render on top of it.
    private transient boolean livePreviewActive = false;
    private transient String livePreviewText = null;
    private transient Point livePreviewLoc = null;
    private transient String livePreviewEditingId = null;



    // Flattening tolerance (map units) for curve->polyline conversion in eraser math
    private static final double ERASE_FLATNESS = 0.75;

    // All eraser cutting is done in a finer integer grid (prevents stuck micro-segments)
    private static final int ERASE_SCALE = 4;

    // Text preview (draw-only)
    private static final String TEXT_PREVIEW_SAMPLE = "Abc";
    private static final int TEXT_PREVIEW_ALPHA = 110;

    // Toolbar
    private JToggleButton btnDraw, btnText, btnShapes, btnGum;
    private JButton btnClear;
    private JComboBox<String> layerCombo;
    private JButton btnAddLayer, btnDeleteLayer;
    private javax.swing.JLabel layerLabel;

    // ------------------- Side Settings -------------------
    private static class SideSetting {
        String sideName;
        boolean canDraw;
        Color drawColor;
        Color textColor;
        boolean visibleToAll;
    }

    private static final String OBSERVER_SIDE = "<observer>";

    // ------------------- Layer -------------------
    private static class Layer {
        String id;
        String name;
        String side;

        Layer(String id, String name, String side) {
            this.id = id;
            this.name = name;
            this.side = side;
        }

        String getDisplayName() {
            if (side == null || side.isEmpty()) return name;
            return name + " (" + side + ")";
        }
    }

    // ------------------- SVG Data Structures -------------------
    private static class SvgPath {
        String id;
        int rgb;
        double w;
        String side;
        String layerId;

        static class Subpath {
            double startX, startY;
            ArrayList<Seg> segs = new ArrayList<>();
            Subpath(double sx, double sy) { startX = sx; startY = sy; }
        }

        interface Seg {}

        static class LineTo implements Seg {
            double x, y;
            LineTo(double x, double y) { this.x = x; this.y = y; }
        }

        static class CubicTo implements Seg {
            double x1, y1, x2, y2, x, y;
            CubicTo(double x1, double y1, double x2, double y2, double x, double y) {
                this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.x = x; this.y = y;
            }
        }

        ArrayList<Subpath> subs = new ArrayList<>();

        // Cached MAP-space bounds (conservative for cubics via control points)
        Rectangle bounds = new Rectangle();
        boolean boundsValid = false;

        SvgPath(String id, int rgb, double w) {
            this.id = id;
            this.rgb = rgb;
            this.w = w;
        }

        SvgPath(String id, int rgb, double w, String svgData) {
            this.id = id;
            this.rgb = rgb;
            this.w = w;
            parseSvg(svgData);
        }

        void invalidateBounds() { boundsValid = false; }

        Rectangle getBounds() {
            if (!boundsValid) recomputeBounds();
            return bounds;
        }

        private void recomputeBounds() {
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;

            for (Subpath sp : subs) {
                minX = Math.min(minX, sp.startX); maxX = Math.max(maxX, sp.startX);
                minY = Math.min(minY, sp.startY); maxY = Math.max(maxY, sp.startY);

                for (Seg s : sp.segs) {
                    if (s instanceof LineTo) {
                        LineTo l = (LineTo) s;
                        minX = Math.min(minX, l.x); maxX = Math.max(maxX, l.x);
                        minY = Math.min(minY, l.y); maxY = Math.max(maxY, l.y);
                    }
                    else if (s instanceof CubicTo) {
                        CubicTo c = (CubicTo) s;
                        minX = Math.min(minX, Math.min(Math.min(c.x1, c.x2), c.x));
                        maxX = Math.max(maxX, Math.max(Math.max(c.x1, c.x2), c.x));
                        minY = Math.min(minY, Math.min(Math.min(c.y1, c.y2), c.y));
                        maxY = Math.max(maxY, Math.max(Math.max(c.y1, c.y2), c.y));
                    }
                }
            }

            if (!Double.isFinite(minX) || !Double.isFinite(minY)) {
                bounds = new Rectangle(0, 0, 0, 0);
            }
            else {
                int x = (int) Math.floor(minX);
                int y = (int) Math.floor(minY);
                int w = (int) Math.ceil(maxX) - x;
                int h = (int) Math.ceil(maxY) - y;
                bounds = new Rectangle(x, y, Math.max(1, w), Math.max(1, h));
            }
            boundsValid = true;
        }

        boolean hasCurves() {
            for (Subpath sp : subs) for (Seg s : sp.segs) if (s instanceof CubicTo) return true;
            return false;
        }

        void parseSvg(String data) {
            subs.clear();
            if (data == null || data.trim().isEmpty()) { invalidateBounds(); return; }

            String[] t = data.trim().split("\\s+");
            int i = 0;
            Subpath cur = null;

            while (i < t.length) {
                String cmd = t[i++];

                if ("M".equals(cmd) && i + 1 < t.length) {
                    double x = Double.parseDouble(t[i++]);
                    double y = Double.parseDouble(t[i++]);
                    cur = new Subpath(x, y);
                    subs.add(cur);
                }
                else if ("L".equals(cmd) && cur != null && i + 1 < t.length) {
                    double x = Double.parseDouble(t[i++]);
                    double y = Double.parseDouble(t[i++]);
                    cur.segs.add(new LineTo(x, y));
                }
                else if ("C".equals(cmd) && cur != null && i + 5 < t.length) {
                    double x1 = Double.parseDouble(t[i++]);
                    double y1 = Double.parseDouble(t[i++]);
                    double x2 = Double.parseDouble(t[i++]);
                    double y2 = Double.parseDouble(t[i++]);
                    double x = Double.parseDouble(t[i++]);
                    double y = Double.parseDouble(t[i++]);
                    cur.segs.add(new CubicTo(x1, y1, x2, y2, x, y));
                }
                else if ("Z".equals(cmd) || "z".equals(cmd)) {
                    // Close path: line back to subpath start
                    if (cur != null) cur.segs.add(new LineTo(cur.startX, cur.startY));
                }
            }

            invalidateBounds();
        }

        String toSvgData() {
            StringBuilder sb = new StringBuilder();
            for (Subpath sp : subs) {
                sb.append("M ").append(fmt(sp.startX)).append(" ").append(fmt(sp.startY)).append(" ");
                for (Seg s : sp.segs) {
                    if (s instanceof LineTo) {
                        LineTo l = (LineTo) s;
                        sb.append("L ").append(fmt(l.x)).append(" ").append(fmt(l.y)).append(" ");
                    }
                    else if (s instanceof CubicTo) {
                        CubicTo c = (CubicTo) s;
                        sb.append("C ")
                                .append(fmt(c.x1)).append(" ").append(fmt(c.y1)).append(" ")
                                .append(fmt(c.x2)).append(" ").append(fmt(c.y2)).append(" ")
                                .append(fmt(c.x)).append(" ").append(fmt(c.y)).append(" ");
                    }
                }
            }
            return sb.toString().trim();
        }

        Path2D.Double buildMapPath() {
            Path2D.Double p = new Path2D.Double(Path2D.WIND_NON_ZERO);
            for (Subpath sp : subs) {
                p.moveTo(sp.startX, sp.startY);
                for (Seg s : sp.segs) {
                    if (s instanceof LineTo) {
                        LineTo l = (LineTo) s;
                        p.lineTo(l.x, l.y);
                    }
                    else if (s instanceof CubicTo) {
                        CubicTo c = (CubicTo) s;
                        p.curveTo(c.x1, c.y1, c.x2, c.y2, c.x, c.y);
                    }
                }
            }
            return p;
        }

        ArrayList<ArrayList<Point>> toScaledPolylineSubpaths(double flatness, int scale) {
            ArrayList<ArrayList<Point>> out = new ArrayList<>();
            if (subs.isEmpty()) return out;

            if (!hasCurves()) {
                for (Subpath sp : subs) {
                    ArrayList<Point> pts = new ArrayList<>();
                    pts.add(new Point((int) Math.round(sp.startX * scale), (int) Math.round(sp.startY * scale)));
                    for (Seg s : sp.segs) {
                        LineTo l = (LineTo) s;
                        Point np = new Point((int) Math.round(l.x * scale), (int) Math.round(l.y * scale));
                        if (!np.equals(pts.get(pts.size() - 1))) pts.add(np);
                    }
                    if (pts.size() >= 2) out.add(pts);
                }
                return out;
            }

            PathIterator it = buildMapPath().getPathIterator(null, flatness);
            double[] c = new double[6];
            ArrayList<Point> cur = null;

            while (!it.isDone()) {
                int seg = it.currentSegment(c);
                if (seg == PathIterator.SEG_MOVETO) {
                    if (cur != null && cur.size() >= 2) out.add(cur);
                    cur = new ArrayList<>();
                    cur.add(new Point((int) Math.round(c[0] * scale), (int) Math.round(c[1] * scale)));
                }
                else if (seg == PathIterator.SEG_LINETO) {
                    if (cur == null) cur = new ArrayList<>();
                    Point np = new Point((int) Math.round(c[0] * scale), (int) Math.round(c[1] * scale));
                    if (cur.isEmpty() || !np.equals(cur.get(cur.size() - 1))) cur.add(np);
                }
                it.next();
            }
            if (cur != null && cur.size() >= 2) out.add(cur);
            return out;
        }

        void setFromScaledPolylineSubpaths(ArrayList<ArrayList<Point>> newSubs, int scale) {
            subs.clear();
            if (newSubs == null) { invalidateBounds(); return; }

            for (ArrayList<Point> pts : newSubs) {
                if (pts == null || pts.size() < 2) continue;

                double sx = pts.get(0).x / (double) scale;
                double sy = pts.get(0).y / (double) scale;
                Subpath sp = new Subpath(sx, sy);

                for (int i = 1; i < pts.size(); i++) {
                    Point p = pts.get(i);
                    sp.segs.add(new LineTo(p.x / (double) scale, p.y / (double) scale));
                }
                subs.add(sp);
            }

            invalidateBounds();
        }

        private static String fmt(double v) {
            long r = Math.round(v);
            if (Math.abs(v - r) < 1e-9) return Long.toString(r);
            String s = String.format(Locale.US, "%.4f", v);
            while (s.indexOf('.') >= 0 && s.endsWith("0")) s = s.substring(0, s.length() - 1);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
            return s;
        }
    }

    private static class TextItem {
        String id, fontName, text, side;
        String layerId;
        int x, y, rgb, fontSize;
        TextItem(String id, int x, int y, int rgb, String fontName, int fontSize, String text) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.x = x; this.y = y; this.rgb = rgb;
            this.fontName = fontName; this.fontSize = fontSize; this.text = text;
        }
    }

    // ------------------- Custom Shape (designer-imported SVG) -------------------
    private static class CustomShape {
        final String name;
        final SvgPath template;
        final double normMinX, normMinY, normW, normH;
        // Placement style: false = Box (corner-to-corner, screen-aligned),
        // true = Directed (tail at p1, head at p2, rotated/scaled along the vector)
        final boolean directed;

        CustomShape(String name, String svgData, boolean directed) {
            this.name = name;
            this.directed = directed;
            this.template = new SvgPath(UUID.randomUUID().toString(), 0, 1.0, svgData);
            Rectangle b = template.getBounds();
            this.normMinX = b.x;
            this.normMinY = b.y;
            this.normW = Math.max(1.0, b.width);
            this.normH = Math.max(1.0, b.height);
        }
    }

    // ------------------- VASSAL Component Wiring -------------------
    @Override public String getConfigureName() { return "Drawing Annotator"; }
    @Override public Class<?>[] getAllowableConfigureComponents() { return new Class<?>[0]; }
    @Override public HelpFile getHelpFile() { return null; }

    @Override
    public String[] getAttributeNames() {
        return new String[] {
                "drawingEnabled", "textEnabled", "shapesEnabled",
                "independentSides", "multilayerDrawing", "sideSettings",
                "drawColor", "lineWidth", "eraserRadius", "font", "textColor",
                "btnDrawText", "btnTextText", "btnShapesText", "btnGumText", "btnClearText",
                "btnDrawIcon", "btnTextIcon", "btnShapesIcon", "btnGumIcon", "btnClearIcon",
                "customShapes",
                "hkDraw", "hkText", "hkShapes", "hkGum", "hkClear"
        };
    }

    @Override
    public String[] getAttributeDescriptions() {
        return new String[] {
                "Enable Drawing?", "Enable Text?", "Enable Shapes?",
                "Independent drawings for each side?", "Multilayer Drawing?", "Side Drawing Settings",
                "Draw Color", "Line Width", "Eraser Radius",
                "Font", "Text Color",
                "Draw Button Tooltip", "Text Button Tooltip", "Shapes Button Tooltip", "Gum Button Tooltip", "Clear Button Tooltip",
                "Draw Button Icon", "Text Button Icon", "Shapes Button Icon", "Gum Button Icon", "Clear Button Icon",
                "Custom Shapes",
                "Draw Hotkey", "Text Hotkey", "Shapes Hotkey", "Gum Hotkey", "Clear Hotkey"
        };
    }

    @Override
    public Class<?>[] getAttributeTypes() {
        return new Class<?>[] {
                Boolean.class, Boolean.class, Boolean.class,
                Boolean.class, Boolean.class, SideSettingsConfig.class,
                Color.class, Double.class, Integer.class,
                FontConfig.class, Color.class,
                String.class, String.class, String.class, String.class, String.class,
                IconConfig.class, IconConfig.class, IconConfig.class, IconConfig.class, IconConfig.class,
                CustomShapesConfig.class,
                NamedKeyStroke.class, NamedKeyStroke.class, NamedKeyStroke.class, NamedKeyStroke.class, NamedKeyStroke.class
        };
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (value == null) return;
        if (value instanceof Color) {
            String colorStr = ColorConfigurer.colorToString((Color) value);
            if (key.equals("drawColor")) drawColor = (Color) value;
            else if (key.equals("textColor")) textColor = (Color) value;
            return;
        }
        if (value instanceof NamedKeyStroke) {
            if (key.equals("hkDraw")) hkDraw = (NamedKeyStroke) value;
            else if (key.equals("hkText")) hkText = (NamedKeyStroke) value;
            else if (key.equals("hkShapes")) hkShapes = (NamedKeyStroke) value;
            else if (key.equals("hkGum")) hkGum = (NamedKeyStroke) value;
            else if (key.equals("hkClear")) hkClear = (NamedKeyStroke) value;
            return;
        }
        // VASSAL's AutoConfigurer passes Font OBJECTS from FontConfigurer when the
        // designer edits the font; only module loading passes encoded strings.
        // Font.toString() is not decodable, so without this branch the chosen
        // font was silently dropped and the module kept saving SansSerif.
        if (value instanceof Font) {
            if (key.equals("font")) font = (Font) value;
            return;
        }
        String v = value.toString();
        try {
            if (key.equals("drawingEnabled")) { drawingEnabled = Boolean.parseBoolean(v); try { updateButtonStates(); } catch (Exception ex) {} }
            else if (key.equals("textEnabled")) { textEnabled = Boolean.parseBoolean(v); try { updateButtonStates(); } catch (Exception ex) {} }
            else if (key.equals("shapesEnabled")) { shapesEnabled = Boolean.parseBoolean(v); try { updateButtonStates(); } catch (Exception ex) {} }
            else if (key.equals("independentSides")) { independentSides = Boolean.parseBoolean(v); try { updateButtonStates(); refreshLayerCombo(); } catch (Exception ex) {} }
            else if (key.equals("multilayerDrawing")) { multilayerDrawing = Boolean.parseBoolean(v); try { if (multilayerDrawing) ensureNoneLayer(); updateLayerUIVisibility(); updateButtonStates(); refreshLayerCombo(); } catch (Exception ex) {} }
            else if (key.equals("sideSettings")) { sideSettingsEncoded = v; parseSideSettings(v); }
            else if (key.equals("drawColor")) drawColor = ColorConfigurer.stringToColor(v);
            else if (key.equals("lineWidth")) drawWidth = Double.parseDouble(v);
            else if (key.equals("eraserRadius")) eraserRadius = Integer.parseInt(v);
            else if (key.equals("font")) font = FontConfigurer.decode(v);
            else if (key.equals("textColor")) textColor = ColorConfigurer.stringToColor(v);
            else if (key.equals("btnDrawText")) btnDrawText = v;
            else if (key.equals("btnTextText")) btnTextText = v;
            else if (key.equals("btnShapesText")) btnShapesText = v;
            else if (key.equals("btnGumText")) btnGumText = v;
            else if (key.equals("btnClearText")) btnClearText = v;
            else if (key.equals("btnDrawIcon")) { btnDrawIcon = v; if (btnDraw != null) applyButtonIcon(btnDraw, v); }
            else if (key.equals("btnTextIcon")) { btnTextIcon = v; if (btnText != null) applyButtonIcon(btnText, v); }
            else if (key.equals("btnShapesIcon")) { btnShapesIcon = v; if (btnShapes != null) applyButtonIcon(btnShapes, v); }
            else if (key.equals("btnGumIcon")) { btnGumIcon = v; if (btnGum != null) applyButtonIcon(btnGum, v); }
            else if (key.equals("btnClearIcon")) { btnClearIcon = v; if (btnClear != null) applyButtonIcon(btnClear, v); }
            else if (key.equals("customShapes")) { customShapesEncoded = v; parseCustomShapes(v); }
            else if (key.equals("hkDraw")) hkDraw = NamedHotKeyConfigurer.decode(v);
            else if (key.equals("hkText")) hkText = NamedHotKeyConfigurer.decode(v);
            else if (key.equals("hkShapes")) hkShapes = NamedHotKeyConfigurer.decode(v);
            else if (key.equals("hkGum")) hkGum = NamedHotKeyConfigurer.decode(v);
            else if (key.equals("hkClear")) hkClear = NamedHotKeyConfigurer.decode(v);
        } catch (NumberFormatException ignored) {}
    }

    @Override
    public String getAttributeValueString(String key) {
        if (key.equals("drawingEnabled")) return String.valueOf(drawingEnabled);
        else if (key.equals("textEnabled")) return String.valueOf(textEnabled);
        else if (key.equals("shapesEnabled")) return String.valueOf(shapesEnabled);
        else if (key.equals("independentSides")) return String.valueOf(independentSides);
        else if (key.equals("multilayerDrawing")) return String.valueOf(multilayerDrawing);
        else if (key.equals("sideSettings")) return sideSettingsEncoded;
        else if (key.equals("drawColor")) return ColorConfigurer.colorToString(drawColor);
        else if (key.equals("lineWidth")) return String.valueOf(drawWidth);
        else if (key.equals("eraserRadius")) return String.valueOf(eraserRadius);
        else if (key.equals("font")) return FontConfigurer.encode(font);
        else if (key.equals("textColor")) return ColorConfigurer.colorToString(textColor);
        else if (key.equals("btnDrawText")) return btnDrawText;
        else if (key.equals("btnTextText")) return btnTextText;
        else if (key.equals("btnShapesText")) return btnShapesText;
        else if (key.equals("btnGumText")) return btnGumText;
        else if (key.equals("btnClearText")) return btnClearText;
        else if (key.equals("btnDrawIcon")) return btnDrawIcon;
        else if (key.equals("btnTextIcon")) return btnTextIcon;
        else if (key.equals("btnShapesIcon")) return btnShapesIcon;
        else if (key.equals("btnGumIcon")) return btnGumIcon;
        else if (key.equals("btnClearIcon")) return btnClearIcon;
        else if (key.equals("customShapes")) return customShapesEncoded;
        else if (key.equals("hkDraw")) return hkDraw == null ? null : NamedHotKeyConfigurer.encode(hkDraw);
        else if (key.equals("hkText")) return hkText == null ? null : NamedHotKeyConfigurer.encode(hkText);
        else if (key.equals("hkShapes")) return hkShapes == null ? null : NamedHotKeyConfigurer.encode(hkShapes);
        else if (key.equals("hkGum")) return hkGum == null ? null : NamedHotKeyConfigurer.encode(hkGum);
        else if (key.equals("hkClear")) return hkClear == null ? null : NamedHotKeyConfigurer.encode(hkClear);
        return null;
    }

    @Override
    public void removeFrom(Buildable parent) {
        if (map != null) {
            map.removeDrawComponent(this);
            // Only pop the stack if we actually own the slot right now.
            if (listenerOnStack) {
                map.popMouseListener(this);
                listenerOnStack = false;
            }
            if (map.getView() != null) map.getView().removeMouseMotionListener(this);

            if (btnDraw != null) map.getToolBar().remove(btnDraw);
            if (btnText != null) map.getToolBar().remove(btnText);
            if (btnShapes != null) map.getToolBar().remove(btnShapes);
            if (btnGum != null) map.getToolBar().remove(btnGum);
            if (btnClear != null) map.getToolBar().remove(btnClear);
            if (layerCombo != null) map.getToolBar().remove(layerCombo);
            if (btnAddLayer != null) map.getToolBar().remove(btnAddLayer);
            if (btnDeleteLayer != null) map.getToolBar().remove(btnDeleteLayer);
        }
        livePreviewActive = false;
        livePreviewEditingId = null;
        // Unregister side change listener
        try {
            if (sideChangeListener != null) {
                PlayerRoster pr = GameModule.getGameModule().getPlayerRoster();
                if (pr != null) pr.removeSideChangeListenerFromInstance(sideChangeListener);
                sideChangeListener = null;
            }
        } catch (Exception ex) {}
        GameModule.getGameModule().removeCommandEncoder(this);
        GameModule.getGameModule().getGameState().removeGameComponent(this);
        mode = Mode.OFF;
    }

    @Override
    public void addTo(Buildable parent) {
        if (parent instanceof Map) {
            this.map = (Map) parent;
            map.addDrawComponent(this);
            GameModule.getGameModule().addCommandEncoder(this);
            GameModule.getGameModule().getGameState().addGameComponent(this);
            setupToolbar();

            // Register for side change events (Retire button) to refresh layer list
            try {
                PlayerRoster pr = GameModule.getGameModule().getPlayerRoster();
                if (pr != null) {
                    sideChangeListener = (oldSide, newSide) -> {
                        SwingUtilities.invokeLater(() -> {
                            // Reset to None layer on side change
                            activeLayerId = null;
                            refreshLayerCombo();
                            updateButtonStates();
                            if (map != null) map.repaint();
                        });
                    };
                    pr.addSideChangeListenerToInstance(sideChangeListener);
                }
            } catch (Exception ex) {}

            SwingUtilities.invokeLater(() -> {
                if (map != null && map.getView() != null) {
                    // IMPORTANT: We intentionally do NOT call map.pushMouseListener(this)
                    // here. VASSAL's Map.mousePressed/mouseReleased/mouseClicked dispatch
                    // EXCLUSIVELY to the top of the mouseListenerStack (it is an
                    // if/else-if with the normal local-mouse-listener multicaster), and
                    // the piece-drag DragGestureListener is only recognised while the
                    // stack is empty (see Map.addTo's DragGestureListener guard:
                    // `mouseListenerStack.isEmpty()`). Permanently occupying the stack
                    // would therefore prevent users from ever selecting or moving tokens.
                    //
                    // Instead we push/pop ourselves on demand in setMode() (via
                    // updateStackMembership()) whenever a drawing tool is toggled on/off.
                    // While Mode == OFF the stack is empty and the engine handles mouse
                    // input normally; while a tool is active we own the mouse events for
                    // drawing. We still keep the MouseMotionListener attached full-time
                    // (it is harmless: Map does not route motion events through the
                    // stack, and it early-returns when OFF).
                    map.getView().addMouseMotionListener(this);
                    updateStackMembership();
                }
            });
        }
    }

    private void setupToolbar() {
        JToolBar tb = map.getToolBar();
        tb.addSeparator();

        btnDraw = new JToggleButton(btnDrawText);
        btnText = new JToggleButton(btnTextText);
        btnShapes = new JToggleButton(btnShapesText);
        btnGum = new JToggleButton(btnGumText);
        btnClear = new JButton(btnClearText);

        btnDraw.addActionListener(e -> handleToggle(btnDraw, Mode.DRAW));
        btnText.addActionListener(e -> handleToggle(btnText, Mode.TEXT));
        btnGum.addActionListener(e -> handleToggle(btnGum, Mode.GUM));

        updateButtonStates();
        setupShapesMenu();

        btnClear.addActionListener(e -> {
            if (multilayerDrawing) {
                String activeId = (activeLayerId == null) ? NONE_LAYER_ID : activeLayerId;
                // The None layer is implicit runtime state: it may legitimately be
                // absent from the layers list (e.g. after setup(false) wiped it or
                // in older saved games). Never abort Clear just because it is not
                // in the list -- getLayerDisplayName(null) reports it as "None".
                Layer active = getLayerById(activeId);
                if (!canModifyActiveLayer()) return;
                String msg = "Clear all drawings on layer '" + getLayerDisplayName(active) + "'?";
                if (JOptionPane.showConfirmDialog(map.getView(), msg, "Clear",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    Command c = new AnnotateCommand(map.getId(), "CLEAR_LAYER", activeId);
                    c.execute(); GameModule.getGameModule().sendAndLog(c);
                }
            } else {
                String msg = independentSides ? "Clear all your drawings on this map?" : "Clear all drawings on this map?";
                if (JOptionPane.showConfirmDialog(map.getView(), msg, "Clear",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    if (independentSides) {
                        Command c = new AnnotateCommand(map.getId(), "CLEAR_SIDE", getMySideOrEmpty());
                        c.execute(); GameModule.getGameModule().sendAndLog(c);
                    } else {
                        Command c = new AnnotateCommand(map.getId(), "CLEAR_ALL", "");
                        c.execute(); GameModule.getGameModule().sendAndLog(c);
                    }
                }
            }
        });

        tb.add(btnDraw); tb.add(btnText); tb.add(btnShapes); tb.add(btnGum); tb.add(btnClear);
        applyButtonIcon(btnDraw, btnDrawIcon);
        applyButtonIcon(btnText, btnTextIcon);
        applyButtonIcon(btnShapes, btnShapesIcon);
        applyButtonIcon(btnGum, btnGumIcon);
        applyButtonIcon(btnClear, btnClearIcon);
        bindHotkey(hkDraw, btnDraw);
        bindHotkey(hkText, btnText);
        bindHotkey(hkShapes, btnShapes);
        bindHotkey(hkGum, btnGum);
        bindHotkey(hkClear, btnClear);

        // Layer UI (only visible when multilayerDrawing is enabled)
        tb.addSeparator();
        layerLabel = new javax.swing.JLabel("Layer:");
        layerCombo = new JComboBox<>();
        layerCombo.addActionListener(e -> {
            if (suppressComboListener) return;
            if (layerCombo.getSelectedIndex() >= 0) {
                String displayName = (String) layerCombo.getSelectedItem();
                for (Layer l : layers) {
                    if (getLayerDisplayName(l).equals(displayName)) {
                        activeLayerId = l.id;
                        if (map != null) map.repaint();
                        updateButtonStates();
                        updateLayerButtonStates();
                        break;
                    }
                }
            }
        });
        btnAddLayer = new JButton("+");
        btnAddLayer.setToolTipText("Add Layer");
        btnAddLayer.addActionListener(e -> addLayerDialog());
        btnDeleteLayer = new JButton("−");
        btnDeleteLayer.setToolTipText("Delete Layer");
        btnDeleteLayer.addActionListener(e -> deleteLayerDialog());
        tb.add(layerLabel); tb.add(layerCombo); tb.add(btnAddLayer); tb.add(btnDeleteLayer);
        updateLayerUIVisibility();
        refreshLayerCombo();
    }

    private void updateButtonStates() {
        boolean onNoneLayer = multilayerDrawing && (activeLayerId == null || activeLayerId.equals(NONE_LAYER_ID));
        // With independent sides, "Can Draw" gates the SHARED None layer only.
        // Sides with drawing disabled may still create and draw inside their
        // OWN layers -- they just cannot draw on the shared None layer.
        boolean canDraw;
        if (multilayerDrawing && !onNoneLayer) canDraw = true;
        else canDraw = canCurrentSideDraw();
        canDraw = canDraw && canModifyActiveLayer();
        if (btnDraw != null) btnDraw.setEnabled(drawingEnabled && canDraw);
        if (btnText != null) btnText.setEnabled(textEnabled && canDraw);
        if (btnShapes != null) btnShapes.setEnabled(shapesEnabled && canDraw);
        if (btnGum != null) btnGum.setEnabled(canDraw);
        if (btnClear != null) btnClear.setEnabled(canDraw);
    }

    private void updateLayerUIVisibility() {
        boolean vis = multilayerDrawing;
        if (layerLabel != null) layerLabel.setVisible(vis);
        if (layerCombo != null) layerCombo.setVisible(vis);
        if (btnAddLayer != null) btnAddLayer.setVisible(vis);
        if (btnDeleteLayer != null) btnDeleteLayer.setVisible(vis);
    }

    private void updateLayerButtonStates() {
        boolean canModify = canModifyActiveLayer();
        if (btnDeleteLayer != null) btnDeleteLayer.setEnabled(canModify && activeLayerId != null);
        // Any side may create its own layers -- even sides with "Can Draw"
        // disabled (they draw on their own layers, just not on None).
        if (btnAddLayer != null) btnAddLayer.setEnabled(true);
    }

    private void refreshLayerCombo() {
        if (layerCombo == null) return;
        if (multilayerDrawing) ensureNoneLayer();
        suppressComboListener = true;
        layerCombo.removeAllItems();
        String mySide = getMySideOrEmpty();
        for (Layer l : layers) {
            if (!isLayerVisible(l)) continue;
            layerCombo.addItem(getLayerDisplayName(l));
            if (l.id.equals(activeLayerId)) {
                layerCombo.setSelectedItem(getLayerDisplayName(l));
            }
        }
        // If active layer is null or not in the visible list, select first available
        if (activeLayerId == null || layerCombo.getSelectedIndex() < 0) {
            if (layerCombo.getItemCount() > 0) {
                String displayName = (String) layerCombo.getItemAt(0);
                for (Layer l : layers) {
                    if (isLayerVisible(l) && getLayerDisplayName(l).equals(displayName)) {
                        activeLayerId = l.id;
                        layerCombo.setSelectedIndex(0);
                        break;
                    }
                }
            } else {
                activeLayerId = null;
            }
        }
        updateLayerButtonStates();
        updateButtonStates();
        suppressComboListener = false;
        if (map != null) map.repaint();
    }

    private void addLayerDialog() {
        String name = JOptionPane.showInputDialog(map.getView(), "Enter layer name:", "New Layer", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();
        String side = multilayerDrawing && independentSides ? getMySideOrEmpty() : "";
        String id = UUID.randomUUID().toString();
        Command c = new AnnotateCommand(map.getId(), "ADD_LAYER", id + ";" + name + ";" + side);
        c.execute(); GameModule.getGameModule().sendAndLog(c);
        activeLayerId = id;
        refreshLayerCombo();
        updateButtonStates();
    }

    private void deleteLayerDialog() {
        if (activeLayerId == null) return;
        Layer active = getLayerById(activeLayerId);
        if (active == null) return;
        if (active.id.equals(NONE_LAYER_ID)) {
            JOptionPane.showMessageDialog(map.getView(), "The None layer cannot be deleted.", "Cannot Delete", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!canModifyLayer(active)) {
            JOptionPane.showMessageDialog(map.getView(), "You can only delete your own layers.", "Cannot Delete", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(map.getView(),
            "Delete layer '" + getLayerDisplayName(active) + "' and all its drawings?", "Delete Layer", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        Command c = new AnnotateCommand(map.getId(), "DELETE_LAYER", activeLayerId);
        c.execute(); GameModule.getGameModule().sendAndLog(c);
        activeLayerId = null;
        refreshLayerCombo();
    }

    private Layer getLayerById(String id) {
        if (id == null) return null;
        for (Layer l : layers) if (l.id.equals(id)) return l;
        return null;
    }

    private String getLayerDisplayName(Layer l) {
        if (l == null) return "None";
        if (!independentSides || l.side == null || l.side.isEmpty()) return l.name;
        return l.name + " (" + l.side + ")";
    }

    private void ensureNoneLayer() {
        if (getLayerById(NONE_LAYER_ID) == null) {
            layers.add(0, new Layer(NONE_LAYER_ID, "None", ""));
        }
    }

    private boolean isLayerVisible(Layer l) {
        if (!multilayerDrawing) return false;
        if (l.id.equals(NONE_LAYER_ID)) return true;
        if (!independentSides) return true;
        if (l.side == null || l.side.isEmpty()) return false;
        String mySide = getMySideOrEmpty();
        if (mySide.equals(l.side)) return true;
        SideSetting ss = getSideSetting(l.side);
        return ss != null && ss.visibleToAll;
    }

    private boolean canModifyLayer(Layer l) {
        if (!multilayerDrawing) return true;
        if (!independentSides) return true;
        if (l == null) return false;
        if (l.side == null || l.side.isEmpty()) return false;
        String mySide = getMySideOrEmpty();
        return mySide.equals(l.side);
    }

    private boolean canModifyActiveLayer() {
        if (!multilayerDrawing) return true;
        String activeId = (activeLayerId == null) ? NONE_LAYER_ID : activeLayerId;
        if (activeId.equals(NONE_LAYER_ID)) return true;
        Layer l = getLayerById(activeId);
        return canModifyLayer(l);
    }

    private boolean isItemOnVisibleLayer(String itemLayerId) {
        if (!multilayerDrawing) return true;
        String effectiveLayerId = (itemLayerId == null || itemLayerId.isEmpty()) ? NONE_LAYER_ID : itemLayerId;
        String activeId = (activeLayerId == null) ? NONE_LAYER_ID : activeLayerId;
        return effectiveLayerId.equals(activeId);
    }

    private void setupShapesMenu() {
        // The popup is built fresh each time it's shown so that dynamically
        // added custom shapes always appear.
        btnShapes.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) showShapesMenu(e.getX(), e.getY());
            }
        });
        btnShapes.addActionListener(e -> handleToggle(btnShapes, Mode.SHAPE));
    }

    private void showShapesMenu(int x, int y) {
        JPopupMenu shapeMenu = new JPopupMenu();

        ActionListener shapeSelect = e -> {
            String cmd = e.getActionCommand();
            if (cmd.equals("Arrow")) { currentShape = ShapeType.ARROW; selectedCustomShape = null; }
            else if (cmd.equals("Rectangle")) { currentShape = ShapeType.RECTANGLE; selectedCustomShape = null; }
            else if (cmd.equals("Ellipse")) { currentShape = ShapeType.ELLIPSE; selectedCustomShape = null; }
            else {
                currentShape = ShapeType.CUSTOM;
                selectedCustomShape = null;
                for (CustomShape cs : customShapesList) {
                    if (cs.name.equals(cmd)) { selectedCustomShape = cs; break; }
                }
            }
            btnShapes.setText(cmd + " ▼");
            if (!btnShapes.isSelected()) btnShapes.doClick();
            else setMode(Mode.SHAPE);
        };

        JMenuItem miArrow = new JMenuItem("Arrow");
        JMenuItem miRect = new JMenuItem("Rectangle");
        JMenuItem miOval = new JMenuItem("Ellipse");
        miArrow.addActionListener(shapeSelect);
        miRect.addActionListener(shapeSelect);
        miOval.addActionListener(shapeSelect);
        shapeMenu.add(miArrow); shapeMenu.add(miRect); shapeMenu.add(miOval);

        if (!customShapesList.isEmpty()) {
            shapeMenu.addSeparator();
            for (CustomShape cs : customShapesList) {
                JMenuItem mi = new JMenuItem(cs.name);
                mi.addActionListener(shapeSelect);
                shapeMenu.add(mi);
            }
        }

        shapeMenu.show(btnShapes, x, y);
    }

    private void bindHotkey(NamedKeyStroke nks, AbstractButton btn) {
        if (nks == null || nks.getKeyStroke() == null) return;
        KeyStroke ks = nks.getKeyStroke();
        SwingUtilities.invokeLater(() -> {
            if (map != null && map.getView() != null) {
                JComponent jcomp = (JComponent) map.getView();
                String actionName = "DrawHotkey_" + UUID.randomUUID().toString();
                jcomp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, actionName);
                jcomp.getActionMap().put(actionName, new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent e) { btn.doClick(); }
                });
            }
        });
    }

    private void handleToggle(JToggleButton clickedBtn, Mode targetMode) {
        if (clickedBtn.isSelected()) {
            if (clickedBtn != btnDraw) btnDraw.setSelected(false);
            if (clickedBtn != btnText) btnText.setSelected(false);
            if (clickedBtn != btnShapes) btnShapes.setSelected(false);
            if (clickedBtn != btnGum)  btnGum.setSelected(false);
            setMode(targetMode);
        }
        else setMode(Mode.OFF);
    }

    private void setMode(Mode m) {
        this.mode = m;
        dragging = false;
        inProgressPoints = null;
        eraserPath = null;
        shapeStart = null;
        previewPaths = null;
        previewTexts = null;
        if (m != Mode.TEXT) { livePreviewActive = false; livePreviewEditingId = null; }

        // Take / release the map's mouseListenerStack depending on whether a
        // drawing mode is active. While active we own mousePressed/Released/
        // Clicked (so the engine won't try to select/drag pieces underneath
        // our strokes); while OFF the stack is empty and the engine is free
        // to read mouse input normally -- this is what lets users move tokens.
        updateStackMembership();

        if (map != null) map.repaint();
    }

    /**
     * Ensures this annotator is pushed onto the map's mouseListenerStack iff a
     * drawing mode is active.
     *
     * VASSAL's {@link Map} dispatches mousePressed / mouseReleased / mouseClicked
     * to the TOP of the mouseListenerStack only (it is an if/else-if against the
     * normal local-mouse-listener multicaster), and its piece-drag
     * DragGestureListener is suppressed whenever the stack is non-empty
     * (see the `mouseListenerStack.isEmpty()` guard in Map.addTo).
     *
     * Therefore we must occupy the stack ONLY while drawing. This is exactly
     * what restores the ability to select and move tokens whenever no draw
     * tool is enabled.
     */
    private void updateStackMembership() {
        if (map == null) return;
        final boolean wantOnStack = (mode != Mode.OFF);
        if (wantOnStack && !listenerOnStack) {
            map.pushMouseListener(this);
            listenerOnStack = true;
        }
        else if (!wantOnStack && listenerOnStack) {
            map.popMouseListener(this);
            listenerOnStack = false;
        }
    }

    // ------------------- Mouse Listener -------------------
    @Override
    public void mousePressed(MouseEvent e) {
        if (mode == Mode.OFF || map == null) return;

        // map.pushMouseListener delivers MAP coords
        final Point mapLoc = e.getPoint();

        if (isLeftDown(e)) {
            if (multilayerDrawing && !canModifyActiveLayer()) return;
            if (!multilayerDrawing && independentSides && !canCurrentSideDraw()) return;
            if (multilayerDrawing && independentSides && !canCurrentSideDraw()) {
                // Inverted permission: "Can Draw" gates the SHARED None layer
                // only. Drawing-disabled sides may draw on their OWN layers
                // but not on the shared None layer.
                String activeId = (activeLayerId == null) ? NONE_LAYER_ID : activeLayerId;
                if (activeId.equals(NONE_LAYER_ID)) return;
            }
            if (mode == Mode.DRAW) {
                dragging = true;
                inProgressPoints = new ArrayList<>();
                inProgressPoints.add(mapLoc);
                e.consume();
            }
            else if (mode == Mode.SHAPE) {
                dragging = true;
                shapeStart = mapLoc;
                cursorMap = mapLoc;
                previewPaths = new ArrayList<>();
                e.consume();
            }
            else if (mode == Mode.GUM) {
                dragging = true;
                eraserPath = new ArrayList<>();
                eraserPath.add(mapLoc);

                previewPaths = deepCopyPaths(paths);
                previewTexts = deepCopyTexts(texts);
                gumAppliedIdx = 0;

                e.consume();
            }
            else if (mode == Mode.TEXT) {
                handleTextClick(mapLoc);
                e.consume();
            }
            map.repaint();
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (mode == Mode.OFF || map == null) return;

        final Point mapLoc = map.componentToMap(e.getPoint());
        cursorMap = mapLoc;

        if (dragging) {
            if (mode == Mode.DRAW && inProgressPoints != null) {
                addIfFar(inProgressPoints, mapLoc, 2);
                e.consume();
            }
            else if (mode == Mode.SHAPE && shapeStart != null) {
                previewPaths = new ArrayList<>();
                int rgb = getEffectiveDrawColor().getRGB();
                if (currentShape == ShapeType.CUSTOM && selectedCustomShape != null) {
                    SvgPath sp = createCustomShapePath(shapeStart, mapLoc, selectedCustomShape, rgb, drawWidth);
                    if (sp != null) previewPaths.add(sp);
                } else {
                    previewPaths.add(createPureShapePath(shapeStart, mapLoc, currentShape, rgb, drawWidth));
                }
                e.consume();
            }
            else if (mode == Mode.GUM && eraserPath != null) {
                int before = eraserPath.size();
                addIfFar(eraserPath, mapLoc, 2);
                if (eraserPath.size() != before) processGumPreviewIncremental();
                e.consume();
            }
            map.repaint();
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (mode == Mode.OFF || map == null || !dragging) return;
        dragging = false;

        if (mode == Mode.DRAW && inProgressPoints != null && inProgressPoints.size() >= 2) {
            Color effColor = getEffectiveDrawColor();
            String effSide = getMySideOrEmpty();
            SvgPath sp = new SvgPath(UUID.randomUUID().toString(), effColor.getRGB(), drawWidth);
            sp.side = effSide;
            sp.layerId = multilayerDrawing ? activeLayerId : null;
            sp.subs.add(polylineToSubpath(inProgressPoints));
            sp.invalidateBounds();
            Command c = new AnnotateCommand(map.getId(), "ADD_PATH",
                    sp.id + ";" + sp.rgb + ";" + sp.w + ";" + effSide + ";" + sp.toSvgData() + ";" + (sp.layerId != null ? sp.layerId : ""));
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }
        else if (mode == Mode.SHAPE && shapeStart != null && previewPaths != null && !previewPaths.isEmpty()) {
            SvgPath sp = previewPaths.get(0);
            String effSide = getMySideOrEmpty();
            sp.side = effSide;
            sp.layerId = multilayerDrawing ? activeLayerId : null;
            Command c = new AnnotateCommand(map.getId(), "ADD_PATH",
                    sp.id + ";" + sp.rgb + ";" + sp.w + ";" + effSide + ";" + sp.toSvgData() + ";" + (sp.layerId != null ? sp.layerId : ""));
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }
        else if (mode == Mode.GUM && eraserPath != null && !eraserPath.isEmpty()) {
            String effSide = getMySideOrEmpty();
            StringBuilder epStr = new StringBuilder();
            epStr.append(effSide).append(";");
            epStr.append(eraserRadius).append(";");
            for (Point p : eraserPath) epStr.append(p.x).append(",").append(p.y).append(";");
            Command c = new AnnotateCommand(map.getId(), "ERASE_PATH", epStr.toString());
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }

        inProgressPoints = null;
        eraserPath = null;
        shapeStart = null;
        previewPaths = null;
        previewTexts = null;
        map.repaint();
        e.consume();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        // When OFF, stay completely out of the engine's way -- do not track the
        // cursor or trigger repaints. (This listener is attached to the map view
        // full-time via addMouseMotionListener; it must be inert while OFF.)
        if (map == null || mode == Mode.OFF) return;
        cursorMap = map.componentToMap(e.getPoint());

        // TEXT preview follows cursor
        if (mode == Mode.TEXT || mode == Mode.GUM) map.repaint();
    }

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    // ------------------- Text dialog actions (live preview + multiline) -------------------
    private void handleTextClick(Point mapLoc) {
        int idx = findTextHit(mapLoc);
        final TextItem existing = idx >= 0 ? texts.get(idx) : null;
        final Point insertLoc;
        final String initialText;

        if (existing != null) {
            initialText = existing.text;
            insertLoc = new Point(existing.x, existing.y);
        } else {
            Font f = font;
            FontMetrics fm = map.getView().getFontMetrics(f);
            int baselineY = mapLoc.y - (fm.getHeight() / 2) + fm.getAscent();
            insertLoc = new Point(mapLoc.x, baselineY);
            initialText = "";
        }

        // Activate live preview on the map
        livePreviewText = initialText;
        livePreviewLoc = insertLoc;
        livePreviewEditingId = existing != null ? existing.id : null;
        livePreviewActive = true;
        if (map != null) map.repaint();

        // Build a custom modal dialog with a multiline JTextArea.
        // The modal event loop still repaints the map, so the live preview
        // updates on every keystroke via the DocumentListener below.
        final JDialog dialog = new JDialog((Frame) null, existing != null ? "Edit Text" : "Add Text", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(5, 5));

        final JTextArea textArea = new JTextArea(initialText, 5, 35);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setLineWrap(false);

        // Live preview: update preview text on every change
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
            private void update() {
                livePreviewText = textArea.getText();
                if (map != null) map.repaint();
            }
        });

        JLabel helpLabel = new JLabel(
            "<html>Tip: Press <b>Enter</b> to commit. Use <b>Shift+Enter</b> for a new line.<br>" +
            "Click <b>OK</b> to commit. Blank text deletes.</html>");

        JScrollPane scrollPane = new JScrollPane(textArea);
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(helpLabel, BorderLayout.NORTH);
        topPanel.add(scrollPane, BorderLayout.CENTER);
        dialog.add(topPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        JButton okBtn = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");

        // Enter commits the text; Shift+Enter inserts a newline
        textArea.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "commit");
        textArea.getActionMap().put("commit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { okBtn.doClick(); }
        });
        textArea.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "newline");
        textArea.getActionMap().put("newline", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int pos = textArea.getCaretPosition();
                textArea.insert("\n", pos);
            }
        });

        final boolean[] committed = {false};
        okBtn.addActionListener(e -> { committed[0] = true; dialog.dispose(); });
        cancelBtn.addActionListener(e -> dialog.dispose());

        buttonPanel.add(okBtn);
        buttonPanel.add(cancelBtn);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(map.getView());

        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                livePreviewActive = false;
                livePreviewEditingId = null;
            }
        });

        dialog.setVisible(true); // blocks (modal)

        // After dialog closes, commit if OK was pressed
        if (committed[0]) {
            String txt = textArea.getText();
            if (txt == null || txt.trim().isEmpty()) {
                if (existing != null) {
                    Command c = new AnnotateCommand(map.getId(), "REMOVE_TEXT", existing.id);
                    c.execute(); GameModule.getGameModule().sendAndLog(c);
                }
            } else {
                String id = existing != null ? existing.id : UUID.randomUUID().toString();
                String effLayerId = multilayerDrawing ? (activeLayerId != null ? activeLayerId : "") : "";
                String payload = id + ";" + insertLoc.x + ";" + insertLoc.y + ";"
                        + getEffectiveTextColor().getRGB() + ";"
                        + font.getName() + ";" + font.getSize() + ";" + getMySideOrEmpty() + ";" + b64(txt) + ";" + effLayerId;
                Command c = new AnnotateCommand(map.getId(), "ADD_TEXT", payload);
                c.execute(); GameModule.getGameModule().sendAndLog(c);
            }
        }

        livePreviewActive = false;
        livePreviewEditingId = null;
        if (map != null) map.repaint();
    }



    // ------------------- Multiline text bounds -------------------
    private Rectangle getTextBounds(TextItem t, FontMetrics fm) {
        String[] lines = t.text.split("\n", -1);
        int maxWidth = 1;
        for (String line : lines) {
            int w = fm.stringWidth(line);
            if (w > maxWidth) maxWidth = w;
        }
        int lineHeight = fm.getHeight();
        int totalHeight = Math.max(1, lineHeight * lines.length);
        return new Rectangle(t.x, t.y - fm.getAscent(), maxWidth, totalHeight);
    }

    // ------------------- Custom SVG shape helpers -------------------
    private void parseCustomShapes(String encoded) {
        customShapesList.clear();
        if (encoded == null || encoded.trim().isEmpty()) return;
        String[] defs = encoded.split("\\|\\|");
        for (String def : defs) {
            if (def == null || def.trim().isEmpty()) continue;
            int sep = def.indexOf('|');
            if (sep < 0) continue;
            String name = def.substring(0, sep).trim();
            String rest = def.substring(sep + 1).trim();
            // Optional placement-style suffix: "|D" = directed, absent = box.
            boolean directed = false;
            if (rest.endsWith("|D")) { directed = true; rest = rest.substring(0, rest.length() - 2).trim(); }
            String svgData = rest;
            if (name.isEmpty() || svgData.isEmpty()) continue;
            try {
                customShapesList.add(new CustomShape(name, svgData, directed));
            } catch (Exception ignored) {}
        }
    }

    private SvgPath createCustomShapePath(Point p1, Point p2, CustomShape cs, int rgb, double w) {
        SvgPath sp = new SvgPath(UUID.randomUUID().toString(), rgb, w);

        if (cs.directed) {
            // Directed placement: p1 = tail, p2 = head. The shape is scaled
            // uniformly so its width equals the tail->head distance, rotated
            // to follow the drag direction, and centered on the midpoint.
            double dx = p2.x - p1.x, dy = p2.y - p1.y;
            double len = Math.hypot(dx, dy);
            if (len < 1) return null;
            double ang = Math.atan2(dy, dx);
            double s = len / cs.normW;
            double cxN = cs.normMinX + cs.normW / 2.0;
            double cyN = cs.normMinY + cs.normH / 2.0;
            double mx = (p1.x + p2.x) / 2.0, my = (p1.y + p2.y) / 2.0;
            double ca = Math.cos(ang), sa = Math.sin(ang);

            for (SvgPath.Subpath sub : cs.template.subs) {
                double nx = (sub.startX - cxN) * s, ny = (sub.startY - cyN) * s;
                SvgPath.Subpath newSub = new SvgPath.Subpath(mx + ca * nx - sa * ny, my + sa * nx + ca * ny);
                for (SvgPath.Seg seg : sub.segs) {
                    if (seg instanceof SvgPath.LineTo) {
                        SvgPath.LineTo l = (SvgPath.LineTo) seg;
                        double lx = (l.x - cxN) * s, ly = (l.y - cyN) * s;
                        newSub.segs.add(new SvgPath.LineTo(mx + ca * lx - sa * ly, my + sa * lx + ca * ly));
                    } else if (seg instanceof SvgPath.CubicTo) {
                        SvgPath.CubicTo c = (SvgPath.CubicTo) seg;
                        double a1x = (c.x1 - cxN) * s, a1y = (c.y1 - cyN) * s;
                        double a2x = (c.x2 - cxN) * s, a2y = (c.y2 - cyN) * s;
                        double a3x = (c.x - cxN) * s, a3y = (c.y - cyN) * s;
                        newSub.segs.add(new SvgPath.CubicTo(
                                mx + ca * a1x - sa * a1y, my + sa * a1x + ca * a1y,
                                mx + ca * a2x - sa * a2y, my + sa * a2x + ca * a2y,
                                mx + ca * a3x - sa * a3y, my + sa * a3x + ca * a3y));
                    }
                }
                sp.subs.add(newSub);
            }
            sp.invalidateBounds();
            return sp;
        }

        // Box placement (default): corner-to-corner, screen-aligned.
        double bx = Math.min(p1.x, p2.x);
        double by = Math.min(p1.y, p2.y);
        double bw = Math.abs(p1.x - p2.x);
        double bh = Math.abs(p1.y - p2.y);
        if (bw < 1 || bh < 1) return null;

        for (SvgPath.Subpath sub : cs.template.subs) {
            double sx = bx + ((sub.startX - cs.normMinX) / cs.normW) * bw;
            double sy = by + ((sub.startY - cs.normMinY) / cs.normH) * bh;
            SvgPath.Subpath newSub = new SvgPath.Subpath(sx, sy);
            for (SvgPath.Seg seg : sub.segs) {
                if (seg instanceof SvgPath.LineTo) {
                    SvgPath.LineTo l = (SvgPath.LineTo) seg;
                    newSub.segs.add(new SvgPath.LineTo(
                        bx + ((l.x - cs.normMinX) / cs.normW) * bw,
                        by + ((l.y - cs.normMinY) / cs.normH) * bh));
                } else if (seg instanceof SvgPath.CubicTo) {
                    SvgPath.CubicTo c = (SvgPath.CubicTo) seg;
                    newSub.segs.add(new SvgPath.CubicTo(
                        bx + ((c.x1 - cs.normMinX) / cs.normW) * bw,
                        by + ((c.y1 - cs.normMinY) / cs.normH) * bh,
                        bx + ((c.x2 - cs.normMinX) / cs.normW) * bw,
                        by + ((c.y2 - cs.normMinY) / cs.normH) * bh,
                        bx + ((c.x - cs.normMinX) / cs.normW) * bw,
                        by + ((c.y - cs.normMinY) / cs.normH) * bh));
                }
            }
            sp.subs.add(newSub);
        }
        sp.invalidateBounds();
        return sp;
    }

    // ------------------- Button icons -------------------
    private void applyButtonIcon(AbstractButton btn, String iconName) {
        if (btn == null) return;
        if (iconName == null || iconName.trim().isEmpty()) return;
        try {
            ImageOp op = Op.load(iconName.trim());
            if (op != null) {
                Image img = op.getImage();
                if (img != null && img.getWidth(null) > 0) {
                    btn.setIcon(new ImageIcon(img));
                }
            }
        } catch (Exception ignored) {}
    }





    // ------------------- Gum Preview Worker -------------------
    private void processGumPreviewIncremental() {
        if (map == null || !dragging || mode != Mode.GUM) return;
        if (previewPaths == null || previewTexts == null || eraserPath == null) return;

        // Process all NEW eraser segments since last call (no time budget).
        // Each segment is processed exactly once, so cost is proportional
        // to the number of new points — not the total eraser path length.
        // This eliminates both the original lag (6ms budget too short)
        // and the O(m*n) cost of a full replay on every drag.
        while (gumAppliedIdx < eraserPath.size() - 1) {
            Point a = eraserPath.get(gumAppliedIdx);
            Point b = eraserPath.get(gumAppliedIdx + 1);

            ArrayList<Point> step = new ArrayList<>(2);
            step.add(a);
            step.add(b);

            eraseByGeometricClipping(previewPaths, previewTexts, step, eraserRadius, getMySideOrEmpty());
            gumAppliedIdx++;
        }

        map.repaint();
    }

    // ------------------- Shapes -------------------
    private SvgPath createPureShapePath(Point p1, Point p2, ShapeType type, int rgb, double w) {
        SvgPath sp = new SvgPath(UUID.randomUUID().toString(), rgb, w);

        if (type == ShapeType.ARROW) {
            SvgPath.Subpath line = new SvgPath.Subpath(p1.x, p1.y);
            line.segs.add(new SvgPath.LineTo(p2.x, p2.y));
            sp.subs.add(line);

            double angle = Math.atan2(p2.y - p1.y, p2.x - p1.x);
            int head = 20;

            Point h1 = new Point((int) (p2.x - head * Math.cos(angle - Math.PI / 6)),
                    (int) (p2.y - head * Math.sin(angle - Math.PI / 6)));
            Point h2 = new Point((int) (p2.x - head * Math.cos(angle + Math.PI / 6)),
                    (int) (p2.y - head * Math.sin(angle + Math.PI / 6)));

            SvgPath.Subpath s1 = new SvgPath.Subpath(p2.x, p2.y);
            s1.segs.add(new SvgPath.LineTo(h1.x, h1.y));
            sp.subs.add(s1);

            SvgPath.Subpath s2 = new SvgPath.Subpath(p2.x, p2.y);
            s2.segs.add(new SvgPath.LineTo(h2.x, h2.y));
            sp.subs.add(s2);
        }
        else if (type == ShapeType.RECTANGLE) {
            int x = Math.min(p1.x, p2.x), y = Math.min(p1.y, p2.y);
            int rw = Math.abs(p1.x - p2.x), rh = Math.abs(p1.y - p2.y);

            ArrayList<Point> box = new ArrayList<>();
            box.add(new Point(x, y));
            box.add(new Point(x + rw, y));
            box.add(new Point(x + rw, y + rh));
            box.add(new Point(x, y + rh));
            box.add(new Point(x, y));
            sp.subs.add(polylineToSubpath(box));
        }
        else if (type == ShapeType.ELLIPSE) {
            int x = Math.min(p1.x, p2.x), y = Math.min(p1.y, p2.y);
            int rw = Math.abs(p1.x - p2.x), rh = Math.abs(p1.y - p2.y);

            double cx = x + rw / 2.0, cy = y + rh / 2.0;
            double rx = rw / 2.0, ry = rh / 2.0;

            final double k = 0.5522847498307936;
            double ox = rx * k;
            double oy = ry * k;

            SvgPath.Subpath ell = new SvgPath.Subpath(cx + rx, cy);
            ell.segs.add(new SvgPath.CubicTo(cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry));
            ell.segs.add(new SvgPath.CubicTo(cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy));
            ell.segs.add(new SvgPath.CubicTo(cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry));
            ell.segs.add(new SvgPath.CubicTo(cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy));
            sp.subs.add(ell);
        }

        sp.invalidateBounds();
        return sp;
    }

    private SvgPath.Subpath polylineToSubpath(List<Point> pts) {
        SvgPath.Subpath sp = new SvgPath.Subpath(pts.get(0).x, pts.get(0).y);
        for (int i = 1; i < pts.size(); i++) {
            Point p = pts.get(i);
            sp.segs.add(new SvgPath.LineTo(p.x, p.y));
        }
        return sp;
    }

    // ------------------- Network Sync & State -------------------
    @Override public Command decode(String command) {
        return command.startsWith(COMMAND_PREFIX) ? new AnnotateCommand(command.substring(COMMAND_PREFIX.length())) : null;
    }

    @Override public String encode(Command c) {
        return c instanceof AnnotateCommand ? COMMAND_PREFIX + ((AnnotateCommand) c).payload : null;
    }

    @Override public void setup(boolean gameStarting) {
        if (!gameStarting) {
            paths.clear();
            texts.clear();
            layers.clear();
            activeLayerId = null;
            if (map != null) map.repaint();
        }
        if (gameStarting) {
            // The None layer is implicit runtime state (not stored in saved games):
            // always re-create it on game start so drawing/clearing on "None"
            // works even when the saved state contains no layers at all.
            if (multilayerDrawing) {
                ensureNoneLayer();
            }
            SwingUtilities.invokeLater(() -> {
                if (multilayerDrawing) refreshLayerCombo();
                updateButtonStates();
            });
        }
    }

    @Override public Command getRestoreCommand() {
        return new AnnotateCommand(map.getId(), "SET_STATE", encodeState());
    }

    private class AnnotateCommand extends Command {
        String payload;
        AnnotateCommand(String mapId, String action, String data) { this.payload = mapId + "||" + action + "||" + data; }
        AnnotateCommand(String fullPayload) { this.payload = fullPayload; }

        @Override
        protected void executeCommand() {
            if (map == null) return;

            String[] parts = payload.split("\\Q||\\E", 3);
            if (parts.length < 3 || !parts[0].equals(map.getId())) return;

            String action = parts[1], data = parts[2];

            if (action.equals("ADD_PATH")) {
                String[] p = data.split(";", 6);
                paths.removeIf(x -> x.id.equals(p[0]));
                SvgPath sp = new SvgPath(p[0], Integer.parseInt(p[1]), Double.parseDouble(p[2]), p.length > 4 ? p[4] : "");
                sp.side = p.length > 3 ? p[3] : "";
                sp.layerId = p.length > 5 && !p[5].isEmpty() ? p[5] : null;
                paths.add(sp);
            }
            else if (action.equals("ADD_TEXT")) {
                String[] t = data.split(";", 9);
                texts.removeIf(x -> x.id.equals(t[0]));
                TextItem ti = new TextItem(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                        Integer.parseInt(t[3]), t[4], Integer.parseInt(t[5]), unb64(t[7]));
                ti.side = t[6];
                ti.layerId = t.length > 8 && !t[8].isEmpty() ? t[8] : null;
                texts.add(ti);
            }
            else if (action.equals("REMOVE_TEXT")) {
                texts.removeIf(t -> t.id.equals(data));
            }
            else if (action.equals("ERASE_PATH")) {
                String[] eData = data.split(";");
                String eraseSide = eData[0];
                int radius = Integer.parseInt(eData[1]);
                ArrayList<Point> ep = new ArrayList<>();
                for (int i = 2; i < eData.length; i++) {
                    if (eData[i].isEmpty()) continue;
                    String[] xy = eData[i].split(",");
                    if (xy.length != 2) continue;
                    ep.add(new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
                }
                eraseByGeometricClipping(paths, texts, ep, radius, eraseSide);
            }
            else if (action.equals("CLEAR_ALL")) {
                paths.clear(); texts.clear(); layers.clear(); activeLayerId = null;
            }
            else if (action.equals("CLEAR_SIDE")) {
                final String clearSide = data;
                paths.removeIf(sp -> isOwnedBy(sp.side, clearSide));
                texts.removeIf(t -> isOwnedBy(t.side, clearSide));
                layers.removeIf(l -> isOwnedBy(l.side, clearSide));
                if (activeLayerId != null) {
                    Layer al = getLayerById(activeLayerId);
                    if (al != null && isOwnedBy(al.side, clearSide)) activeLayerId = null;
                }
            }
            else if (action.equals("ADD_LAYER")) {
                String[] l = data.split(";", 3);
                layers.add(new Layer(l[0], l.length > 1 ? l[1] : "Unnamed", l.length > 2 ? l[2] : ""));
                SwingUtilities.invokeLater(() -> refreshLayerCombo());
            }
            else if (action.equals("DELETE_LAYER")) {
                final String delLayerId = data;
                if (NONE_LAYER_ID.equals(delLayerId)) return;
                layers.removeIf(l -> l.id.equals(delLayerId));
                paths.removeIf(sp -> delLayerId.equals(sp.layerId));
                texts.removeIf(t -> delLayerId.equals(t.layerId));
                SwingUtilities.invokeLater(() -> refreshLayerCombo());
            }
            else if (action.equals("CLEAR_LAYER")) {
                final String clrLayerId = data;
                paths.removeIf(sp -> clrLayerId.equals(sp.layerId != null ? sp.layerId : NONE_LAYER_ID));
                texts.removeIf(t -> clrLayerId.equals(t.layerId != null ? t.layerId : NONE_LAYER_ID));
            }
            else if (action.equals("SET_STATE")) {
                decodeState(data);
            }

            map.repaint();
        }

        @Override protected Command myUndoCommand() { return null; }
    }

    private String encodeState() {
        ArrayList<String> items = new ArrayList<>();
        for (Layer l : layers) {
            items.add("L;" + l.id + ";" + l.name + ";" + (l.side != null ? l.side : ""));
        }
        for (SvgPath p : paths) {
            String d = p.toSvgData();
            String sd = p.side != null ? p.side : "";
            String ld = p.layerId != null ? p.layerId : "";
            if (!d.isEmpty()) items.add("P;" + p.id + ";" + p.rgb + ";" + p.w + ";" + sd + ";" + ld + ";" + d);
        }
        for (TextItem t : texts) {
            String sd = t.side != null ? t.side : "";
            String ld = t.layerId != null ? t.layerId : "";
            items.add("T;" + t.id + ";" + t.x + ";" + t.y + ";" + t.rgb + ";" + t.fontName + ";" + t.fontSize + ";" + sd + ";" + ld + ";" + b64(t.text));
        }
        return String.join("@@", items);
    }

    private void decodeState(String data) {
        paths.clear(); texts.clear(); layers.clear(); activeLayerId = null;
        if (data == null || data.isEmpty()) return;

        for (String item : data.split("@@")) {
            String[] parts = item.split(";", 2);
            if (parts.length < 2) continue;

            if (parts[0].equals("L")) {
                String[] l = parts[1].split(";", 3);
                layers.add(new Layer(l[0], l.length > 1 ? l[1] : "Unnamed", l.length > 2 ? l[2] : ""));
            }
            else if (parts[0].equals("P")) {
                String[] p = parts[1].split(";", 6);
                SvgPath sp = new SvgPath(p[0], Integer.parseInt(p[1]), Double.parseDouble(p[2]), p.length > 5 ? p[5] : "");
                sp.side = p.length > 3 ? p[3] : "";
                sp.layerId = p.length > 4 && !p[4].isEmpty() ? p[4] : null;
                paths.add(sp);
            }
            else if (parts[0].equals("T")) {
                String[] t = parts[1].split(";", 9);
                TextItem ti = new TextItem(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                        Integer.parseInt(t[3]), t[4], Integer.parseInt(t[5]), unb64(t[8]));
                ti.side = t[6];
                ti.layerId = t.length > 7 && !t[7].isEmpty() ? t[7] : null;
                texts.add(ti);
            }
        }
        ensureNoneLayer();
        SwingUtilities.invokeLater(() -> refreshLayerCombo());
    }

    // ------------------- Drawing -------------------
    @Override public boolean drawAboveCounters() { return true; }

    @Override
    public void draw(Graphics g, Map map) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        final double zoom = map.getZoom();

        ArrayList<SvgPath> pToDraw = (dragging && mode == Mode.GUM && previewPaths != null) ? previewPaths : paths;
        ArrayList<TextItem> tToDraw = (dragging && mode == Mode.GUM && previewTexts != null) ? previewTexts : texts;

        // When multilayerDrawing is on, only show items on the active layer
        if (multilayerDrawing) {
            ArrayList<SvgPath> filteredPaths = new ArrayList<>();
            for (SvgPath sp : pToDraw) {
                if (isItemOnVisibleLayer(sp.layerId)) filteredPaths.add(sp);
            }
            pToDraw = filteredPaths;
            ArrayList<TextItem> filteredTexts = new ArrayList<>();
            for (TextItem t : tToDraw) {
                if (isItemOnVisibleLayer(t.layerId)) filteredTexts.add(t);
            }
            tToDraw = filteredTexts;
        }

        // Draw committed (or gum-preview) paths
        for (SvgPath sp : pToDraw) {
            if (!isPathVisible(sp)) continue;
            drawSvgPath(g2d, map, sp, zoom);
        }

        // Shape preview (while dragging)
        if (dragging && mode == Mode.SHAPE && previewPaths != null) {
            for (SvgPath sp : previewPaths) {
                drawSvgPath(g2d, map, sp, zoom);
            }
        }

        // In-progress freehand
        if (mode == Mode.DRAW && inProgressPoints != null && inProgressPoints.size() > 1) {
            g2d.setColor(getEffectiveDrawColor());
            g2d.setStroke(new BasicStroke((float) (drawWidth * zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < inProgressPoints.size(); i++) {
                Point a = map.mapToComponent(inProgressPoints.get(i - 1));
                Point b = map.mapToComponent(inProgressPoints.get(i));
                g2d.drawLine(a.x, a.y, b.x, b.y);
            }
        }

        // Committed (or gum-preview) texts  (multiline + global-var expansion)
        for (TextItem t : tToDraw) {
            if (!isTextVisible(t)) continue;
            // While live-editing this text, skip it here so the live preview
            // (rendered below) doesn't double up on top of the committed copy.
            if (livePreviewActive && livePreviewEditingId != null && livePreviewEditingId.equals(t.id)) continue;

            g2d.setColor(new Color(t.rgb, true));
            int zFont = Math.max(1, (int) Math.round(t.fontSize * zoom));
            g2d.setFont(new Font(t.fontName, Font.PLAIN, zFont));
            FontMetrics fm = g2d.getFontMetrics();
            String[] lines = t.text.split("\n", -1);
            Point p = map.mapToComponent(new Point(t.x, t.y));
            int lineHeight = fm.getHeight();
            for (int i = 0; i < lines.length; i++) {
                g2d.drawString(lines[i], p.x, p.y + i * lineHeight);
            }
        }

        // Text cursor placement indicator (shown when TEXT mode is on but
        // the edit dialog is NOT open yet — i.e. the user hasn't clicked).
        if (mode == Mode.TEXT && cursorMap != null && !livePreviewActive) {
            int zFont = Math.max(1, (int) Math.round(font.getSize() * zoom));
            Font f = new Font(font.getName(), Font.PLAIN, zFont);
            g2d.setFont(f);
            FontMetrics fm = map.getView().getFontMetrics(f);
            int baselineY = cursorMap.y - (fm.getHeight() / 2) + fm.getAscent();
            Point p = map.mapToComponent(new Point(cursorMap.x, baselineY));
            Color c = new Color(getEffectiveTextColor().getRed(), getEffectiveTextColor().getGreen(), getEffectiveTextColor().getBlue(), TEXT_PREVIEW_ALPHA);
            g2d.setColor(c);
            g2d.drawString(TEXT_PREVIEW_SAMPLE, p.x, p.y);
        }

        // Live text preview (while the edit dialog is open).
        // Renders the in-progress text — including newlines and expanded
        // $(GlobalProperty) placeholders — at the insertion point.
        if (livePreviewActive && livePreviewLoc != null && livePreviewText != null) {
            int zFont = Math.max(1, (int) Math.round(font.getSize() * zoom));
            Font f = new Font(font.getName(), Font.PLAIN, zFont);
            g2d.setFont(f);
            FontMetrics fm = g2d.getFontMetrics();
            String[] lines = livePreviewText.split("\n", -1);
            Point p = map.mapToComponent(livePreviewLoc);
            int lineHeight = fm.getHeight();
            g2d.setColor(new Color(getEffectiveTextColor().getRed(), getEffectiveTextColor().getGreen(), getEffectiveTextColor().getBlue(), 235));
            for (int i = 0; i < lines.length; i++) {
                g2d.drawString(lines[i], p.x, p.y + i * lineHeight);
            }
            // Thin vertical placement marker
            g2d.setColor(new Color(getEffectiveTextColor().getRed(), getEffectiveTextColor().getGreen(), getEffectiveTextColor().getBlue(), 110));
            g2d.setStroke(new BasicStroke(1.0f));
            g2d.drawLine(p.x - 1, p.y - fm.getAscent(),
                         p.x - 1, p.y - fm.getAscent() + lineHeight * lines.length);
        }

        // Eraser cursor
        if (mode == Mode.GUM && cursorMap != null) {
            Point c = map.mapToComponent(cursorMap);
            int rr = (int) Math.round(eraserRadius * zoom);
            g2d.setStroke(new BasicStroke(1.0f));
            g2d.setColor(new Color(0, 0, 0, 80));
            g2d.drawOval(c.x - rr, c.y - rr, 2 * rr, 2 * rr);
            g2d.setColor(new Color(255, 255, 255, 60));
            g2d.drawOval(c.x - rr - 1, c.y - rr - 1, 2 * rr + 2, 2 * rr + 2);
        }

        g2d.dispose();
    }

    private void drawSvgPath(Graphics2D g2d, Map map, SvgPath sp, double zoom) {
        g2d.setColor(new Color(sp.rgb, true));
        g2d.setStroke(new BasicStroke((float) (sp.w * zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if (!sp.hasCurves()) {
            for (SvgPath.Subpath sub : sp.subs) {
                Point prev = map.mapToComponent(new Point((int) Math.round(sub.startX), (int) Math.round(sub.startY)));
                for (SvgPath.Seg seg : sub.segs) {
                    SvgPath.LineTo l = (SvgPath.LineTo) seg;
                    Point cur = map.mapToComponent(new Point((int) Math.round(l.x), (int) Math.round(l.y)));
                    g2d.drawLine(prev.x, prev.y, cur.x, cur.y);
                    prev = cur;
                }
            }
        }
        else {
            Path2D.Double p = new Path2D.Double();
            for (SvgPath.Subpath sub : sp.subs) {
                Point mv = map.mapToComponent(new Point((int) Math.round(sub.startX), (int) Math.round(sub.startY)));
                p.moveTo(mv.x, mv.y);
                for (SvgPath.Seg seg : sub.segs) {
                    if (seg instanceof SvgPath.LineTo) {
                        SvgPath.LineTo l = (SvgPath.LineTo) seg;
                        Point vv = map.mapToComponent(new Point((int) Math.round(l.x), (int) Math.round(l.y)));
                        p.lineTo(vv.x, vv.y);
                    }
                    else if (seg instanceof SvgPath.CubicTo) {
                        SvgPath.CubicTo c = (SvgPath.CubicTo) seg;
                        Point v1 = map.mapToComponent(new Point((int) Math.round(c.x1), (int) Math.round(c.y1)));
                        Point v2 = map.mapToComponent(new Point((int) Math.round(c.x2), (int) Math.round(c.y2)));
                        Point v3 = map.mapToComponent(new Point((int) Math.round(c.x), (int) Math.round(c.y)));
                        p.curveTo(v1.x, v1.y, v2.x, v2.y, v3.x, v3.y);
                    }
                }
            }
            g2d.draw(p);
        }
    }

    // ------------------- Eraser -------------------
    private static class Segment {
        Point a, b;
        Segment(Point a, Point b) { this.a = a; this.b = b; }
    }

    private void eraseByGeometricClipping(ArrayList<SvgPath> pList, ArrayList<TextItem> tList,
                                          ArrayList<Point> rawEpList, int radiusMapUnits, String eraserSide) {
        if (radiusMapUnits <= 0) return;

        final int scale = ERASE_SCALE;
        final int radius = radiusMapUnits * scale;

        ArrayList<Point> scaledRaw = new ArrayList<>(rawEpList.size());
        for (Point p : rawEpList) scaledRaw.add(new Point(p.x * scale, p.y * scale));

        ArrayList<Point> denseEpList = densifyEraserScaled(scaledRaw);
        Rectangle erBox = buildEraserBox(denseEpList, radius);
        if (erBox == null) return;

        if (pList != null) {
            for (SvgPath sp : pList) {
                if (multilayerDrawing && !isItemOnVisibleLayer(sp.layerId)) continue;
                if (independentSides && !isOwnedBy(sp.side, eraserSide)) {
                    // Allow erasing None layer drawings by anyone
                    String effLayerId = (sp.layerId == null || sp.layerId.isEmpty()) ? NONE_LAYER_ID : sp.layerId;
                    if (!(multilayerDrawing && effLayerId.equals(NONE_LAYER_ID))) continue;
                }
                Rectangle spBox = sp.getBounds();
                Rectangle erBoxMap = new Rectangle(
                        (int) Math.floor(erBox.x / (double) scale) - 1,
                        (int) Math.floor(erBox.y / (double) scale) - 1,
                        (int) Math.ceil(erBox.width / (double) scale) + 2,
                        (int) Math.ceil(erBox.height / (double) scale) + 2
                );
                if (!spBox.intersects(erBoxMap)) continue;

                ArrayList<ArrayList<Point>> polySubs = sp.toScaledPolylineSubpaths(ERASE_FLATNESS, scale);
                ArrayList<ArrayList<Point>> newSubpaths = new ArrayList<>();

                for (ArrayList<Point> sub : polySubs) {
                    if (sub.size() < 2) continue;

                    List<Segment> activeSegments = new ArrayList<>();
                    for (int i = 0; i < sub.size() - 1; i++) activeSegments.add(new Segment(sub.get(i), sub.get(i + 1)));

                    for (Point ep : denseEpList) {
                        List<Segment> nextGen = new ArrayList<>();
                        for (Segment s : activeSegments) nextGen.addAll(cutSegmentWithCircle(s, ep, radius));
                        activeSegments = nextGen;
                        if (activeSegments.isEmpty()) break;
                    }

                    if (activeSegments.isEmpty()) continue;

                    ArrayList<Point> cur = new ArrayList<>();
                    cur.add(activeSegments.get(0).a);
                    cur.add(activeSegments.get(0).b);

                    for (int i = 1; i < activeSegments.size(); i++) {
                        Segment s = activeSegments.get(i);
                        if (s.a.equals(cur.get(cur.size() - 1))) cur.add(s.b);
                        else {
                            pruneSubpath(cur);
                            if (cur.size() >= 2) newSubpaths.add(cur);
                            cur = new ArrayList<>();
                            cur.add(s.a);
                            cur.add(s.b);
                        }
                    }
                    pruneSubpath(cur);
                    if (cur.size() >= 2) newSubpaths.add(cur);
                }

                if (!polylineEquals(polySubs, newSubpaths)) {
                    sp.setFromScaledPolylineSubpaths(newSubpaths, scale);
                }
            }
        }

        if (tList != null) {
            for (Point epScaled : denseEpList) {
                final int epX = epScaled.x / scale;
                final int epY = epScaled.y / scale;
                Point ep = new Point(epX, epY);

                tList.removeIf(t -> {
                    if (multilayerDrawing && !isItemOnVisibleLayer(t.layerId)) return false;
                    if (independentSides && !isOwnedBy(t.side, eraserSide)) {
                        // Allow erasing None layer drawings by anyone
                        String effLayerId = (t.layerId == null || t.layerId.isEmpty()) ? NONE_LAYER_ID : t.layerId;
                        if (!(multilayerDrawing && effLayerId.equals(NONE_LAYER_ID))) return false;
                    }
                    FontMetrics fm = map.getView().getFontMetrics(new Font(t.fontName, Font.PLAIN, t.fontSize));
                    Rectangle box = getTextBounds(t, fm);
                    Rectangle exp = new Rectangle(box.x - radiusMapUnits, box.y - radiusMapUnits,
                            box.width + 2 * radiusMapUnits, box.height + 2 * radiusMapUnits);
                    if (!exp.contains(ep)) return false;
                    int cx = Math.max(box.x, Math.min(box.x + box.width, ep.x));
                    int cy = Math.max(box.y, Math.min(box.y + box.height, ep.y));
                    return dist2(ep, new Point(cx, cy)) <= radiusMapUnits * radiusMapUnits;
                });
            }
        }
    }

    private ArrayList<Point> densifyEraserScaled(ArrayList<Point> rawScaled) {
        ArrayList<Point> dense = new ArrayList<>();
        if (rawScaled.isEmpty()) return dense;

        for (int i = 0; i < rawScaled.size(); i++) {
            if (i == 0) dense.add(rawScaled.get(i));
            else {
                Point last = rawScaled.get(i - 1), cur = rawScaled.get(i);
                int dx = cur.x - last.x, dy = cur.y - last.y;
                double d = Math.sqrt(dx * (double) dx + dy * (double) dy);

                int steps = Math.max(1, (int) (d / (2.0 * ERASE_SCALE)));
                for (int j = 1; j <= steps; j++) dense.add(new Point(last.x + dx * j / steps, last.y + dy * j / steps));
            }
        }
        return dense;
    }

    private Rectangle buildEraserBox(ArrayList<Point> denseScaled, int radiusScaled) {
        if (denseScaled == null || denseScaled.isEmpty()) return null;
        Rectangle r = null;
        for (Point ep : denseScaled) {
            Rectangle b = new Rectangle(ep.x - radiusScaled, ep.y - radiusScaled, radiusScaled * 2, radiusScaled * 2);
            if (r == null) r = b;
            else r.add(b);
        }
        return r;
    }

    private List<Segment> cutSegmentWithCircle(Segment seg, Point c, int r) {
        int minx = Math.min(seg.a.x, seg.b.x), maxx = Math.max(seg.a.x, seg.b.x);
        int miny = Math.min(seg.a.y, seg.b.y), maxy = Math.max(seg.a.y, seg.b.y);
        if (maxx < c.x - r || minx > c.x + r || maxy < c.y - r || miny > c.y + r) {
            List<Segment> out = new ArrayList<>(1);
            out.add(seg);
            return out;
        }

        List<Segment> res = new ArrayList<>();
        double ax = seg.a.x, ay = seg.a.y, bx = seg.b.x, by = seg.b.y;
        double cx = c.x, cy = c.y, dx = bx - ax, dy = by - ay, fx = ax - cx, fy = ay - cy;

        double A = dx * dx + dy * dy;
        double B = 2 * (fx * dx + fy * dy);
        double C = fx * fx + fy * fy - r * r;

        if (A < 1e-9) { if (C > 0) res.add(seg); return res; }

        double det = B * B - 4 * A * C;
        List<Double> ts = new ArrayList<>();
        ts.add(0.0);
        if (det >= 0) {
            double sqrtDet = Math.sqrt(det);
            double t1 = (-B - sqrtDet) / (2 * A);
            double t2 = (-B + sqrtDet) / (2 * A);
            if (t1 > 0 && t1 < 1) ts.add(t1);
            if (t2 > 0 && t2 < 1) ts.add(t2);
        }
        ts.add(1.0);
        ts.sort(Double::compare);

        for (int i = 0; i < ts.size() - 1; i++) {
            double tStart = ts.get(i), tEnd = ts.get(i + 1);
            if (tEnd <= tStart) continue;

            double tMid = (tStart + tEnd) / 2.0;
            double mx = ax + tMid * dx, my = ay + tMid * dy;

            if ((mx - cx) * (mx - cx) + (my - cy) * (my - cy) > r * r) {
                Point pStart = new Point((int) Math.round(ax + tStart * dx), (int) Math.round(ay + tStart * dy));
                Point pEnd   = new Point((int) Math.round(ax + tEnd * dx),   (int) Math.round(ay + tEnd * dy));
                if (!pStart.equals(pEnd)) res.add(new Segment(pStart, pEnd));
            }
        }
        return res;
    }

    // ------------------- Hit testing / utilities -------------------
    private int findTextHit(Point local) {
        String mySide = getMySideOrEmpty();
        for (int i = texts.size() - 1; i >= 0; i--) {
            TextItem t = texts.get(i);
            if (multilayerDrawing && !isItemOnVisibleLayer(t.layerId)) continue;
            if (independentSides && !isOwnedBy(t.side, mySide)) {
                // Allow editing None layer texts by anyone
                String effLayerId = (t.layerId == null || t.layerId.isEmpty()) ? NONE_LAYER_ID : t.layerId;
                if (!(multilayerDrawing && effLayerId.equals(NONE_LAYER_ID))) continue;
            }
            FontMetrics fm = map.getView().getFontMetrics(new Font(t.fontName, Font.PLAIN, t.fontSize));
            if (getTextBounds(t, fm).contains(local)) return i;
        }
        return -1;
    }

    private void addIfFar(ArrayList<Point> pts, Point p, int minDist) {
        if (pts.isEmpty() || dist2(pts.get(pts.size() - 1), p) >= minDist * minDist) pts.add(p);
    }

    private int dist2(Point a, Point b) {
        int dx = a.x - b.x, dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private boolean isLeftDown(MouseEvent e) {
        return (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0;
    }

    private ArrayList<SvgPath> deepCopyPaths(ArrayList<SvgPath> src) {
        ArrayList<SvgPath> out = new ArrayList<>(src.size());
        for (SvgPath sp : src) {
            SvgPath cp = new SvgPath(sp.id, sp.rgb, sp.w);
            cp.side = sp.side;
            cp.layerId = sp.layerId;
            for (SvgPath.Subpath sub : sp.subs) {
                SvgPath.Subpath subCp = new SvgPath.Subpath(sub.startX, sub.startY);
                for (SvgPath.Seg seg : sub.segs) {
                    if (seg instanceof SvgPath.LineTo) {
                        SvgPath.LineTo l = (SvgPath.LineTo) seg;
                        subCp.segs.add(new SvgPath.LineTo(l.x, l.y));
                    }
                    else if (seg instanceof SvgPath.CubicTo) {
                        SvgPath.CubicTo c = (SvgPath.CubicTo) seg;
                        subCp.segs.add(new SvgPath.CubicTo(c.x1, c.y1, c.x2, c.y2, c.x, c.y));
                    }
                }
                cp.subs.add(subCp);
            }
            cp.invalidateBounds();
            out.add(cp);
        }
        return out;
    }

    private ArrayList<TextItem> deepCopyTexts(ArrayList<TextItem> src) {
        ArrayList<TextItem> out = new ArrayList<>(src.size());
        for (TextItem t : src) {
            TextItem cp = new TextItem(t.id, t.x, t.y, t.rgb, t.fontName, t.fontSize, t.text);
            cp.side = t.side;
            cp.layerId = t.layerId;
            out.add(cp);
        }
        return out;
    }

    private static boolean polylineEquals(ArrayList<ArrayList<Point>> a, ArrayList<ArrayList<Point>> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            ArrayList<Point> sa = a.get(i), sb = b.get(i);
            if (sa.size() != sb.size()) return false;
            for (int j = 0; j < sa.size(); j++) {
                if (!sa.get(j).equals(sb.get(j))) return false;
            }
        }
        return true;
    }

    private static void pruneSubpath(ArrayList<Point> pts) {
        if (pts == null) return;

        for (int i = pts.size() - 2; i >= 0; i--) {
            if (pts.get(i).equals(pts.get(i + 1))) pts.remove(i + 1);
        }

        for (int i = pts.size() - 1; i >= 1; i--) {
            int dx = pts.get(i).x - pts.get(i - 1).x;
            int dy = pts.get(i).y - pts.get(i - 1).y;
            if (dx * dx + dy * dy <= 1) pts.remove(i);
        }
    }

    // ------------------- Per-side helpers -------------------
    private String getMySide() {
        try {
            return PlayerRoster.getMySide();
        } catch (Exception e) {
            return null;
        }
    }

    private String getMySideOrEmpty() {
        String s = getMySide();
        if (s != null) return s;
        return independentSides ? OBSERVER_SIDE : "";
    }

    private boolean isOwnedBy(String itemSide, String ownerSide) {
        if (itemSide == null || itemSide.isEmpty()) return false;
        return itemSide.equals(ownerSide);
    }

    private SideSetting getSideSetting(String side) {
        if (side == null || side.isEmpty()) return null;
        for (SideSetting ss : sideSettingsList) {
            if (ss.sideName.equals(side)) return ss;
        }
        return null;
    }

    private Color getEffectiveDrawColor() {
        if (independentSides) {
            // Side colors apply everywhere -- including the None layer.
            // (Drawings on the None layer are still tagged with the drawing
            // side, so it would be inconsistent to use the global color there.
            // The old early-return here made per-side colors look broken
            // whenever multilayer drawing was enabled, because "None" is the
            // default active layer.)
            String mySide = getMySideOrEmpty();
            SideSetting ss = getSideSetting(mySide);
            if (ss != null && ss.drawColor != null) return ss.drawColor;
        }
        return drawColor;
    }

    private Color getEffectiveTextColor() {
        if (independentSides) {
            // Side colors apply everywhere -- including the None layer (see
            // getEffectiveDrawColor above).
            String mySide = getMySideOrEmpty();
            SideSetting ss = getSideSetting(mySide);
            if (ss != null && ss.textColor != null) return ss.textColor;
            if (ss != null && ss.drawColor != null) return ss.drawColor;
        }
        return textColor;
    }

    private boolean isPathVisible(SvgPath sp) {
        if (!independentSides) return true;
        // None layer drawings are visible to everyone (like when independentSides is off)
        String effectiveLayerId = (sp.layerId == null || sp.layerId.isEmpty()) ? NONE_LAYER_ID : sp.layerId;
        if (multilayerDrawing && effectiveLayerId.equals(NONE_LAYER_ID)) return true;
        if (sp.side == null || sp.side.isEmpty()) return true;
        String mySide = getMySideOrEmpty();
        if (mySide.equals(sp.side)) return true;
        SideSetting ss = getSideSetting(sp.side);
        return ss != null && ss.visibleToAll;
    }

    private boolean isTextVisible(TextItem t) {
        if (!independentSides) return true;
        // None layer drawings are visible to everyone (like when independentSides is off)
        String effectiveLayerId = (t.layerId == null || t.layerId.isEmpty()) ? NONE_LAYER_ID : t.layerId;
        if (multilayerDrawing && effectiveLayerId.equals(NONE_LAYER_ID)) return true;
        if (t.side == null || t.side.isEmpty()) return true;
        String mySide = getMySideOrEmpty();
        if (mySide.equals(t.side)) return true;
        SideSetting ss = getSideSetting(t.side);
        return ss != null && ss.visibleToAll;
    }

    private boolean canCurrentSideDraw() {
        if (!independentSides) return true;
        String mySide = getMySideOrEmpty();
        if (mySide.isEmpty()) return false;
        SideSetting ss = getSideSetting(mySide);
        return ss == null || ss.canDraw;
    }

    private void parseSideSettings(String encoded) {
        sideSettingsList.clear();
        if (encoded == null || encoded.trim().isEmpty()) return;
        for (String def : encoded.split("\\|\\|")) {
            if (def == null || def.trim().isEmpty()) continue;
            String[] parts = def.split("\\|");
            if (parts.length < 4) continue;
            SideSetting ss = new SideSetting();
            ss.sideName = parts[0];
            ss.canDraw = Boolean.parseBoolean(parts[1]);
            ss.drawColor = ColorConfigurer.stringToColor(parts[2]);
            ss.textColor = parts.length >= 5 ? ColorConfigurer.stringToColor(parts[4]) : ss.drawColor;
            ss.visibleToAll = Boolean.parseBoolean(parts[3]);
            sideSettingsList.add(ss);
        }
    }

    private static String safeFont(String s) {
        return (s == null || s.trim().isEmpty()) ? "SansSerif" : s.trim();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        try { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    // ------------------- SVG Flattener (pure JDK, no external deps) -------------------

    /**
     * Flattens a real .svg file (paths, rects, circles, ellipses, polygons,
     * polylines, lines, nested groups with transforms) into the absolute
     * "M x y L x y C ... Z" path data used by Custom Shapes.
     *
     * Pure JDK: DOM parsing via javax.xml.parsers, path-data / transform
     * parsing implemented here. Arcs and quadratic beziers are converted to
     * cubics; relative commands are converted to absolute. Fills are ignored
     * (the annotator strokes outlines only).
     */
    private static class SvgFlattener {

        private static final double K = 0.5522847498307936; // circle->bezier constant

        // ---------- transforms: {a,b,c,d,e,f} ----------
        private static double[] identity() { return new double[]{1, 0, 0, 1, 0, 0}; }

        private static double[] compose(double[] outer, double[] inner) {
            // returns outer . inner  (apply inner first, then outer)
            double a = outer[0] * inner[0] + outer[2] * inner[1];
            double b = outer[1] * inner[0] + outer[3] * inner[1];
            double c = outer[0] * inner[2] + outer[2] * inner[3];
            double d = outer[1] * inner[2] + outer[3] * inner[3];
            double e = outer[0] * inner[4] + outer[2] * inner[5] + outer[4];
            double f = outer[1] * inner[4] + outer[3] * inner[5] + outer[5];
            return new double[]{a, b, c, d, e, f};
        }

        private static double[] apply(double[] m, double x, double y) {
            return new double[]{m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
        }

        static double[] parseTransform(String text) {
            double[] m = identity();
            if (text == null || text.trim().isEmpty()) return m;
            java.util.regex.Matcher fm = java.util.regex.Pattern
                    .compile("(translate|scale|rotate|matrix|skewX|skewY)\\s*\\(([^)]*)\\)")
                    .matcher(text);
            while (fm.find()) {
                String name = fm.group(1);
                java.util.List<Double> args = new java.util.ArrayList<>();
                java.util.regex.Matcher nm = java.util.regex.Pattern
                        .compile("[-+]?[\\d.]+(?:[eE][-+]?\\d+)?").matcher(fm.group(2));
                while (nm.find()) args.add(Double.parseDouble(nm.group()));
                double[] t = identity();
                switch (name) {
                    case "translate": {
                        double tx = args.size() > 0 ? args.get(0) : 0;
                        double ty = args.size() > 1 ? args.get(1) : 0;
                        t = new double[]{1, 0, 0, 1, tx, ty};
                        break;
                    }
                    case "scale": {
                        double sx = args.size() > 0 ? args.get(0) : 1;
                        double sy = args.size() > 1 ? args.get(1) : sx;
                        t = new double[]{sx, 0, 0, sy, 0, 0};
                        break;
                    }
                    case "rotate": {
                        double ang = Math.toRadians(args.size() > 0 ? args.get(0) : 0);
                        double ca = Math.cos(ang), sa = Math.sin(ang);
                        t = new double[]{ca, sa, -sa, ca, 0, 0};
                        if (args.size() >= 3) {
                            double cx = args.get(1), cy = args.get(2);
                            t = compose(compose(new double[]{1, 0, 0, 1, cx, cy}, t),
                                    new double[]{1, 0, 0, 1, -cx, -cy});
                        }
                        break;
                    }
                    case "matrix": {
                        if (args.size() >= 6)
                            t = new double[]{args.get(0), args.get(1), args.get(2), args.get(3), args.get(4), args.get(5)};
                        break;
                    }
                    case "skewX": {
                        t = new double[]{1, 0, Math.tan(Math.toRadians(args.get(0))), 1, 0, 0};
                        break;
                    }
                    case "skewY": {
                        t = new double[]{1, Math.tan(Math.toRadians(args.get(0))), 0, 1, 0, 0};
                        break;
                    }
                }
                m = compose(t, m);
            }
            return m;
        }

        // ---------- path data parsing ----------
        private static java.util.List<Object> tokenize(String d) {
            java.util.List<Object> toks = new java.util.ArrayList<>();
            int i = 0;
            final int n = d.length();
            while (i < n) {
                char ch = d.charAt(i);
                if (Character.isWhitespace(ch) || ch == ',') { i++; continue; }
                if (Character.isLetter(ch)) { toks.add(String.valueOf(ch)); i++; continue; }
                int start = i;
                if (ch == '+' || ch == '-') i++;
                while (i < n && (Character.isDigit(d.charAt(i)) || d.charAt(i) == '.')) i++;
                if (i < n && (d.charAt(i) == 'e' || d.charAt(i) == 'E')) {
                    i++;
                    if (i < n && (d.charAt(i) == '+' || d.charAt(i) == '-')) i++;
                    while (i < n && Character.isDigit(d.charAt(i))) i++;
                }
                if (i == start) throw new IllegalArgumentException("Bad character in path data at " + i);
                toks.add(Double.parseDouble(d.substring(start, i)));
            }
            return toks;
        }

        private static String fmt(double v) {
            double r = Math.round(v);
            if (Math.abs(v - r) < 1e-9) return Long.toString((long) r);
            String s = String.format(Locale.US, "%.4f", v);
            while (s.indexOf('.') >= 0 && s.endsWith("0")) s = s.substring(0, s.length() - 1);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
            return s;
        }

        /** Parses one path 'd' string; emits absolute M/L/C/Z with transform applied. */
        static void parsePathData(String d, double[] m, StringBuilder out) {
            java.util.List<Object> toks = tokenize(d);
            int[] idx = {0};
            final int n = toks.size();

            char cmd = 0;
            double cx = 0, cy = 0, sx = 0, sy = 0;
            double[] prevC2 = null, prevQc = null;
            boolean started = false;

            while (idx[0] < n) {
                Object o = toks.get(idx[0]);
                if (o instanceof String) { cmd = ((String) o).charAt(0); idx[0]++; }
                if (cmd == 0) throw new IllegalArgumentException("Path data must begin with a command");

                boolean rel = Character.isLowerCase(cmd);
                char C = Character.toUpperCase(cmd);

                if (C == 'M') {
                    double x = take(toks, idx), y = take(toks, idx);
                    if (rel) { x += cx; y += cy; }
                    emit(out, m, 'M', x, y, 0, 0, 0, 0, 2);
                    cx = x; cy = y; sx = x; sy = y;
                    started = true; prevC2 = prevQc = null;
                    cmd = rel ? 'l' : 'L';
                } else if (C == 'L') {
                    double x = take(toks, idx), y = take(toks, idx);
                    if (rel) { x += cx; y += cy; }
                    if (!started) { emit(out, m, 'M', cx, cy, 0, 0, 0, 0, 2); started = true; }
                    emit(out, m, 'L', x, y, 0, 0, 0, 0, 2);
                    cx = x; cy = y; prevC2 = prevQc = null;
                } else if (C == 'H') {
                    double x = take(toks, idx);
                    if (rel) x += cx;
                    if (!started) { emit(out, m, 'M', cx, cy, 0, 0, 0, 0, 2); started = true; }
                    emit(out, m, 'L', x, cy, 0, 0, 0, 0, 2);
                    cx = x; prevC2 = prevQc = null;
                } else if (C == 'V') {
                    double y = take(toks, idx);
                    if (rel) y += cy;
                    if (!started) { emit(out, m, 'M', cx, cy, 0, 0, 0, 0, 2); started = true; }
                    emit(out, m, 'L', cx, y, 0, 0, 0, 0, 2);
                    cy = y; prevC2 = prevQc = null;
                } else if (C == 'C') {
                    double x1 = take(toks, idx), y1 = take(toks, idx);
                    double x2 = take(toks, idx), y2 = take(toks, idx);
                    double x = take(toks, idx), y = take(toks, idx);
                    if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy; }
                    if (!started) { emit(out, m, 'M', cx, cy, 0, 0, 0, 0, 2); started = true; }
                    emit(out, m, 'C', x1, y1, x2, y2, x, y, 6);
                    cx = x; cy = y; prevC2 = new double[]{x2, y2}; prevQc = null;
                } else if (C == 'S') {
                    double x2 = take(toks, idx), y2 = take(toks, idx);
                    double x = take(toks, idx), y = take(toks, idx);
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy; }
                    double x1, y1;
                    if (prevC2 == null) { x1 = cx; y1 = cy; }
                    else { x1 = 2 * cx - prevC2[0]; y1 = 2 * cy - prevC2[1]; }
                    if (!started) { emit(out, m, 'M', cx, cy, 0, 0, 0, 0, 2); started = true; }
                    emit(out, m, 'C', x1, y1, x2, y2, x, y, 6);
                    cx = x; cy = y; prevC2 = new double[]{x2, y2}; prevQc = null;
                } else if (C == 'Q') {
                    double qx = take(toks, idx), qy = take(toks, idx);
                    double x = take(toks, idx), y = take(toks, idx);
                    if (rel) { qx += cx; qy += cy; x += cx; y += cy; }
                    if (!started) { emit(out, m, 'M', cx, cy, 0, 0, 0, 0, 2); started = true; }
                    emitQuad(out, m, cx, cy, qx, qy, x, y);
                    cx = x; cy = y; prevQc = new double[]{qx, qy}; prevC2 = null;
                } else if (C == 'T') {
                    double x = take(toks, idx), y = take(toks, idx);
                    if (rel) { x += cx; y += cy; }
                    double qx, qy;
                    if (prevQc == null) { qx = cx; qy = cy; }
                    else { qx = 2 * cx - prevQc[0]; qy = 2 * cy - prevQc[1]; }
                    if (!started) { emit(out, m, 'M', cx, cy, 0, 0, 0, 0, 2); started = true; }
                    emitQuad(out, m, cx, cy, qx, qy, x, y);
                    cx = x; cy = y; prevQc = new double[]{qx, qy}; prevC2 = null;
                } else if (C == 'A') {
                    double rx = take(toks, idx), ry = take(toks, idx), rot = take(toks, idx);
                    double laf = take(toks, idx), sf = take(toks, idx);
                    double x = take(toks, idx), y = take(toks, idx);
                    if (rel) { x += cx; y += cy; }
                    if (!started) { emit(out, m, 'M', cx, cy, 0, 0, 0, 0, 2); started = true; }
                    for (double[][] seg : arcToCubics(cx, cy, rx, ry, rot, laf > 0.5, sf > 0.5, x, y)) {
                        emit(out, m, 'C', seg[0][0], seg[0][1], seg[1][0], seg[1][1], seg[2][0], seg[2][1], 6);
                    }
                    cx = x; cy = y; prevC2 = prevQc = null;
                } else if (C == 'Z') {
                    if (started) { out.append("Z "); started = false; }
                    cx = sx; cy = sy; prevC2 = prevQc = null;
                } else {
                    throw new IllegalArgumentException("Unsupported path command: " + cmd);
                }
            }
        }

        private static double take(java.util.List<Object> toks, int[] idx) {
            if (idx[0] >= toks.size() || toks.get(idx[0]) instanceof String)
                throw new IllegalArgumentException("Missing parameters for path command");
            return (Double) toks.get(idx[0]++);
        }

        private static void emit(StringBuilder out, double[] m, char cmd,
                                 double x1, double y1, double x2, double y2, double x, double y, int nCoords) {
            double[] p1 = apply(m, x1, y1);
            double[] p2 = apply(m, x2, y2);
            double[] p3 = apply(m, x, y);
            out.append(cmd);
            if (nCoords >= 2) out.append(' ').append(fmt(p1[0])).append(' ').append(fmt(p1[1]));
            if (nCoords >= 6) out.append(' ').append(fmt(p2[0])).append(' ').append(fmt(p2[1]))
                    .append(' ').append(fmt(p3[0])).append(' ').append(fmt(p3[1]));
            out.append(' ');
        }

        private static void emitQuad(StringBuilder out, double[] m,
                                     double cx, double cy, double qx, double qy, double x, double y) {
            double c1x = cx + 2.0 / 3.0 * (qx - cx);
            double c1y = cy + 2.0 / 3.0 * (qy - cy);
            double c2x = x + 2.0 / 3.0 * (qx - x);
            double c2y = y + 2.0 / 3.0 * (qy - y);
            emit(out, m, 'C', c1x, c1y, c2x, c2y, x, y, 6);
        }

        /** SVG elliptical arc -> cubic bezier segments (endpoint parameterization). */
        private static java.util.List<double[][]> arcToCubics(double x1, double y1,
                double rx, double ry, double xrot, boolean largeArc, boolean sweep, double x2, double y2) {
            java.util.List<double[][]> segs = new java.util.ArrayList<>();
            if (rx == 0 || ry == 0 || (x1 == x2 && y1 == y2)) return segs;
            rx = Math.abs(rx); ry = Math.abs(ry);
            double phi = Math.toRadians(xrot % 360);
            double cosp = Math.cos(phi), sinp = Math.sin(phi);

            double dx2 = (x1 - x2) / 2.0, dy2 = (y1 - y2) / 2.0;
            double x1p = cosp * dx2 + sinp * dy2;
            double y1p = -sinp * dx2 + cosp * dy2;

            double lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
            if (lam > 1.0) { double s = Math.sqrt(lam); rx *= s; ry *= s; }

            double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
            double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
            if (den == 0) return segs;
            double co = Math.sqrt(Math.max(0.0, num / den));
            if (largeArc == sweep) co = -co;
            double cxp = co * rx * y1p / ry;
            double cyp = -co * ry * x1p / rx;
            double cx = cosp * cxp - sinp * cyp + (x1 + x2) / 2.0;
            double cy = sinp * cxp + cosp * cyp + (y1 + y2) / 2.0;

            double theta1 = angleBetween(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
            double dtheta = angleBetween((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
            if (!sweep && dtheta > 0) dtheta -= 2 * Math.PI;
            if (sweep && dtheta < 0) dtheta += 2 * Math.PI;

            int nseg = Math.max(1, (int) Math.ceil(Math.abs(dtheta) / (Math.PI / 2.0) - 1e-9));
            double delta = dtheta / nseg;
            double k = 4.0 / 3.0 * Math.tan(delta / 4.0);

            double th = theta1, px = x1, py = y1;
            for (int s = 0; s < nseg; s++) {
                double th2 = th + delta;
                double c1v = Math.cos(th), s1v = Math.sin(th);
                double c2v = Math.cos(th2), s2v = Math.sin(th2);
                double[] e = ellipsePt(rx, ry, cosp, sinp, cx, cy, c2v, s2v);
                double[] d1 = ellipseDeriv(rx, ry, cosp, sinp, c1v, s1v);
                double[] d2 = ellipseDeriv(rx, ry, cosp, sinp, c2v, s2v);
                double l1 = Math.hypot(d1[0], d1[1]); if (l1 == 0) l1 = 1;
                double l2 = Math.hypot(d2[0], d2[1]); if (l2 == 0) l2 = 1;
                segs.add(new double[][]{
                        {px + k * d1[0] / l1, py + k * d1[1] / l1},
                        {e[0] - k * d2[0] / l2, e[1] - k * d2[1] / l2},
                        {e[0], e[1]}});
                px = e[0]; py = e[1];
                th = th2;
            }
            return segs;
        }

        private static double angleBetween(double ux, double uy, double vx, double vy) {
            double dot = ux * vx + uy * vy;
            double ln = Math.sqrt(ux * ux + uy * uy) * Math.sqrt(vx * vx + vy * vy);
            double a = ln == 0 ? 0 : Math.acos(Math.max(-1.0, Math.min(1.0, dot / ln)));
            if (ux * vy - uy * vx < 0) a = -a;
            return a;
        }

        private static double[] ellipsePt(double rx, double ry, double cosp, double sinp,
                                          double cx, double cy, double cosv, double sinv) {
            double ex = rx * cosv, ey = ry * sinv;
            return new double[]{cosp * ex - sinp * ey + cx, sinp * ex + cosp * ey + cy};
        }

        private static double[] ellipseDeriv(double rx, double ry, double cosp, double sinp,
                                             double cosv, double sinv) {
            double dxu = -rx * sinv, dyu = ry * cosv;
            return new double[]{cosp * dxu - sinp * dyu, sinp * dxu + cosp * dyu};
        }

        // ---------- basic shapes ----------
        private static void shapeRect(double x, double y, double w, double h,
                                      double rx, double ry, double[] m, StringBuilder out) {
            rx = Math.min(Math.max(rx, 0), w / 2.0);
            ry = Math.min(Math.max(ry, 0), h / 2.0);
            if (rx <= 0 || ry <= 0) {
                emit(out, m, 'M', x, y, 0, 0, 0, 0, 2);
                emit(out, m, 'L', x + w, y, 0, 0, 0, 0, 2);
                emit(out, m, 'L', x + w, y + h, 0, 0, 0, 0, 2);
                emit(out, m, 'L', x, y + h, 0, 0, 0, 0, 2);
                emit(out, m, 'L', x, y, 0, 0, 0, 0, 2);
            } else {
                double kx = rx * K, ky = ry * K;
                emit(out, m, 'M', x + rx, y, 0, 0, 0, 0, 2);
                emit(out, m, 'L', x + w - rx, y, 0, 0, 0, 0, 2);
                emit(out, m, 'C', x + w - rx + kx, y, x + w, y + ry, x + w, y + ry, 6);
                emit(out, m, 'L', x + w, y + h - ry, 0, 0, 0, 0, 2);
                emit(out, m, 'C', x + w, y + h - ry + ky, x + w - rx, y + h, x + w - rx, y + h, 6);
                emit(out, m, 'L', x + rx, y + h, 0, 0, 0, 0, 2);
                emit(out, m, 'C', x + rx - kx, y + h, x, y + h - ry, x, y + h - ry, 6);
                emit(out, m, 'L', x, y + ry, 0, 0, 0, 0, 2);
                emit(out, m, 'C', x, y + ry - ky, x + rx, y, x + rx, y, 6);
            }
            out.append("Z ");
        }

        private static void shapeEllipse(double cx, double cy, double rx, double ry,
                                         double[] m, StringBuilder out) {
            if (rx <= 0 || ry <= 0) return;
            double kx = rx * K, ky = ry * K;
            emit(out, m, 'M', cx + rx, cy, 0, 0, 0, 0, 2);
            emit(out, m, 'C', cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry, 6);
            emit(out, m, 'C', cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy, 6);
            emit(out, m, 'C', cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry, 6);
            emit(out, m, 'C', cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy, 6);
            out.append("Z ");
        }

        private static void shapePolygon(String points, boolean close, double[] m, StringBuilder out) {
            if (points == null) return;
            java.util.List<Double> nums = new java.util.ArrayList<>();
            java.util.regex.Matcher nm = java.util.regex.Pattern
                    .compile("[-+]?[\\d.]+(?:[eE][-+]?\\d+)?").matcher(points);
            while (nm.find()) nums.add(Double.parseDouble(nm.group()));
            if (nums.size() < 4) return;
            emit(out, m, 'M', nums.get(0), nums.get(1), 0, 0, 0, 0, 2);
            for (int j = 2; j + 1 < nums.size(); j += 2)
                emit(out, m, 'L', nums.get(j), nums.get(j + 1), 0, 0, 0, 0, 2);
            if (close) out.append("Z ");
        }

        // ---------- document walk ----------
        private static String localName(org.w3c.dom.Node n) {
            String name = n.getNodeName();
            int i = name.indexOf(':');
            return i >= 0 ? name.substring(i + 1) : name;
        }

        private static double attr(org.w3c.dom.Element el, String name, double def) {
            String v = el.getAttribute(name);
            if (v == null || v.trim().isEmpty()) return def;
            try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return def; }
        }

        private static void walk(org.w3c.dom.Element el, double[] m, StringBuilder out) {
            String tag = localName(el);
            String tr = el.getAttribute("transform");
            if (tr != null && !tr.trim().isEmpty()) m = compose(parseTransform(tr), m);

            if (tag.equals("g") || tag.equals("svg") || tag.equals("a")) {
                org.w3c.dom.NodeList children = el.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    org.w3c.dom.Node c = children.item(i);
                    if (c instanceof org.w3c.dom.Element) walk((org.w3c.dom.Element) c, m, out);
                }
                return;
            }
            if (tag.equals("defs") || tag.equals("symbol") || tag.equals("clipPath")
                    || tag.equals("mask") || tag.equals("marker") || tag.equals("metadata")
                    || tag.equals("text") || tag.equals("tspan") || tag.equals("use")
                    || tag.equals("style") || tag.equals("title") || tag.equals("desc")) {
                return;
            }

            try {
                if (tag.equals("path")) {
                    String d = el.getAttribute("d");
                    if (d != null && !d.trim().isEmpty()) parsePathData(d, m, out);
                } else if (tag.equals("rect")) {
                    double x = attr(el, "x", 0), y = attr(el, "y", 0);
                    double w = attr(el, "width", 0), h = attr(el, "height", 0);
                    if (w > 0 && h > 0) {
                        double rx = attr(el, "rx", attr(el, "r", 0));
                        double ry = attr(el, "ry", attr(el, "r", 0));
                        if (rx == 0 && ry > 0) rx = ry;
                        if (ry == 0 && rx > 0) ry = rx;
                        shapeRect(x, y, w, h, rx, ry, m, out);
                    }
                } else if (tag.equals("circle")) {
                    double r = attr(el, "r", 0);
                    if (r > 0) shapeEllipse(attr(el, "cx", 0), attr(el, "cy", 0), r, r, m, out);
                } else if (tag.equals("ellipse")) {
                    double rx = attr(el, "rx", 0), ry = attr(el, "ry", 0);
                    if (rx > 0 && ry > 0) shapeEllipse(attr(el, "cx", 0), attr(el, "cy", 0), rx, ry, m, out);
                } else if (tag.equals("line")) {
                    emit(out, m, 'M', attr(el, "x1", 0), attr(el, "y1", 0), 0, 0, 0, 0, 2);
                    emit(out, m, 'L', attr(el, "x2", 0), attr(el, "y2", 0), 0, 0, 0, 0, 2);
                } else if (tag.equals("polygon")) {
                    shapePolygon(el.getAttribute("points"), true, m, out);
                } else if (tag.equals("polyline")) {
                    shapePolygon(el.getAttribute("points"), false, m, out);
                }
            } catch (RuntimeException ex) {
                System.err.println("MapAnnotator: skipping <" + tag + "> during SVG import: " + ex);
            }
        }

        /** Flattens a .svg file into absolute M/L/C/Z path data; "" if nothing convertible. */
        static String flatten(java.io.File file) throws Exception {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document doc = db.parse(file);
            StringBuilder out = new StringBuilder();
            walk(doc.getDocumentElement(), identity(), out);
            return out.toString().trim();
        }
    }

    // ------------------- Configurer Factories -------------------

    /** Factory for the font picker (family + size in one widget). */
    public static class FontConfig implements ConfigurerFactory {
        @Override
        public Configurer getConfigurer(AutoConfigurable c, String key, String name) {
            // Seed the picker with the component's CURRENT font so reopening
            // the editor shows what was saved instead of always SansSerif 20.
            Font current = null;
            try {
                String enc = c == null ? null : c.getAttributeValueString(key);
                if (enc != null && !enc.trim().isEmpty()) current = FontConfigurer.decode(enc);
            } catch (Exception ignored) {}
            if (current == null) current = new Font("SansSerif", Font.PLAIN, 20);
            return new FontConfigurer(key, name,
                current,
                new int[]{8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 64});
        }
    }

    /** Factory for the button icon pickers (image browser). */
    public static class IconConfig implements ConfigurerFactory {
        @Override
        public Configurer getConfigurer(AutoConfigurable c, String key, String name) {
            return new IconConfigurer(key, name, null);
        }
    }

    /** Factory for the per-side drawing settings editor. */
    public static class SideSettingsConfig implements ConfigurerFactory {
        @Override
        public Configurer getConfigurer(AutoConfigurable c, String key, String name) {
            return new SideSettingsConfigurer(key, name);
        }
    }

    /** Factory for the custom shapes editor. */
    public static class CustomShapesConfig implements ConfigurerFactory {
        @Override
        public Configurer getConfigurer(AutoConfigurable c, String key, String name) {
            return new CustomShapesConfigurer(key, name);
        }
    }

    // ------------------- Side Settings Configurer -------------------

    private static class SideSettingsConfigurer extends Configurer {
        private JPanel controls;
        private final java.util.List<SideSetting> settings = new java.util.ArrayList<>();

        public SideSettingsConfigurer(String key, String name) {
            super(key, name);
        }

        @Override
        public String getValueString() { return encodeSettings(); }

        @Override
        public void setValue(String s) { parseSettings(s); setValue((Object) s); }

        @Override
        public java.awt.Component getControls() {
            if (controls == null) {
                controls = new JPanel(new BorderLayout(5, 5));
                JButton configBtn = new JButton("Configure Side Settings...");
                configBtn.addActionListener(e -> showDialog());
                controls.add(configBtn, BorderLayout.CENTER);
            }
            return controls;
        }

        private void showDialog() {
            try {
                PlayerRoster pr = GameModule.getGameModule().getPlayerRoster();
                if (pr != null) {
                    for (String side : pr.getSides()) {
                        boolean found = false;
                        for (SideSetting ss : settings) {
                            if (ss.sideName.equals(side)) { found = true; break; }
                        }
                        if (!found) {
                            SideSetting ss = new SideSetting();
                            ss.sideName = side; ss.canDraw = true; ss.drawColor = Color.RED; ss.textColor = Color.RED; ss.visibleToAll = false;
                            settings.add(ss);
                        }
                    }
                }
            } catch (Exception ex) {}

            // Always ensure <observer> side exists
            boolean observerFound = false;
            for (SideSetting ss : settings) {
                if (ss.sideName.equals(OBSERVER_SIDE)) { observerFound = true; break; }
            }
            if (!observerFound) {
                SideSetting ss = new SideSetting();
                ss.sideName = OBSERVER_SIDE; ss.canDraw = true; ss.drawColor = Color.BLACK; ss.textColor = Color.BLACK; ss.visibleToAll = false;
                settings.add(ss);
            }

            javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(
                new Object[]{"Side", "Can Draw", "Draw Color", "Text Color", "Visible to All"}, 0) {
                @Override public Class<?> getColumnClass(int col) {
                    if (col == 1 || col == 4) return Boolean.class;
                    return Object.class;
                }
                @Override public boolean isCellEditable(int row, int col) { return col != 0; }
            };

            for (SideSetting ss : settings) {
                model.addRow(new Object[]{ss.sideName, ss.canDraw, ss.drawColor, ss.textColor != null ? ss.textColor : ss.drawColor, ss.visibleToAll});
            }

            javax.swing.JTable table = new javax.swing.JTable(model);
            table.setRowHeight(26);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

            table.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object val, boolean sel, boolean focus, int row, int col) {
                    setBackground(val instanceof Color ? (Color) val : Color.GRAY);
                    setText("");
                    return this;
                }
            });
            table.getColumnModel().getColumn(2).setCellEditor(new ColorCellEditor());
            table.getColumnModel().getColumn(3).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object val, boolean sel, boolean focus, int row, int col) {
                    setBackground(val instanceof Color ? (Color) val : Color.GRAY);
                    setText("");
                    return this;
                }
            });
            table.getColumnModel().getColumn(3).setCellEditor(new ColorCellEditor());

            JDialog dialog = new JDialog((Frame) null, "Side Drawing Settings", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setLayout(new BorderLayout(5, 5));
            dialog.add(new javax.swing.JScrollPane(table), BorderLayout.CENTER);

            JPanel btnPanel = new JPanel();
            JButton okBtn = new JButton("OK");
            JButton cancelBtn = new JButton("Cancel");

            okBtn.addActionListener(e -> {
                settings.clear();
                for (int i = 0; i < model.getRowCount(); i++) {
                    SideSetting ss = new SideSetting();
                    ss.sideName = (String) model.getValueAt(i, 0);
                    ss.canDraw = (Boolean) model.getValueAt(i, 1);
                    ss.drawColor = (Color) model.getValueAt(i, 2);
                    ss.textColor = (Color) model.getValueAt(i, 3);
                    ss.visibleToAll = (Boolean) model.getValueAt(i, 4);
                    settings.add(ss);
                }
                setValue((Object) encodeSettings());
                dialog.dispose();
            });
            cancelBtn.addActionListener(e -> dialog.dispose());

            btnPanel.add(okBtn); btnPanel.add(cancelBtn);
            dialog.add(btnPanel, BorderLayout.SOUTH);

            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
        }

        private static class ColorCellEditor extends javax.swing.AbstractCellEditor implements javax.swing.table.TableCellEditor {
            private Color currentColor;
            private final JButton button = new JButton();
            ColorCellEditor() {
                button.addActionListener(e -> {
                    Color c = JColorChooser.showDialog(button, "Choose Color", currentColor);
                    if (c != null) { currentColor = c; button.setBackground(c); }
                    fireEditingStopped();
                });
            }
            @Override public Component getTableCellEditorComponent(JTable t, Object val, boolean sel, int row, int col) {
                currentColor = val instanceof Color ? (Color) val : Color.RED;
                button.setBackground(currentColor);
                return button;
            }
            @Override public Object getCellEditorValue() { return currentColor; }
        }

        private void parseSettings(String s) {
            settings.clear();
            if (s == null || s.trim().isEmpty()) return;
            for (String def : s.split("\\|\\|")) {
                if (def == null || def.trim().isEmpty()) continue;
                String[] parts = def.split("\\|");
                if (parts.length < 4) continue;
                SideSetting ss = new SideSetting();
                ss.sideName = parts[0]; ss.canDraw = Boolean.parseBoolean(parts[1]);
                ss.drawColor = ColorConfigurer.stringToColor(parts[2]);
                ss.textColor = parts.length >= 5 ? ColorConfigurer.stringToColor(parts[4]) : ss.drawColor;
                ss.visibleToAll = Boolean.parseBoolean(parts[3]);
                settings.add(ss);
            }
        }

        private String encodeSettings() {
            StringBuilder sb = new StringBuilder();
            for (SideSetting ss : settings) {
                if (sb.length() > 0) sb.append("||");
                sb.append(ss.sideName).append("|").append(ss.canDraw).append("|")
                  .append(ColorConfigurer.colorToString(ss.drawColor)).append("|")
                  .append(ss.visibleToAll).append("|")
                  .append(ss.textColor != null ? ColorConfigurer.colorToString(ss.textColor) : ColorConfigurer.colorToString(ss.drawColor));
            }
            return sb.toString();
        }
    }

    // ------------------- Custom Shapes Configurer -------------------

    /**
     * A Configurer providing a table-based editor for custom SVG shapes.
     * Encodes/decodes the same Name|SVGpath||Name|SVGpath||... format
     * used by the original raw-string field, so it is fully backward-compatible.
     */
    private static class CustomShapesConfigurer extends Configurer {
        private JPanel controls;
        private final java.util.List<String[]> rows = new java.util.ArrayList<>();
        private javax.swing.JTable table;
        private javax.swing.table.DefaultTableModel model;

        CustomShapesConfigurer(String key, String name) {
            super(key, name);
        }

        @Override
        public String getValueString() {
            return encodeRows();
        }

        @Override
        public void setValue(String s) {
            parseRows(s);
            setValue((Object) s);
        }

        @Override
        public java.awt.Component getControls() {
            if (controls == null) {
                controls = new JPanel(new BorderLayout(5, 5));

                model = new javax.swing.table.DefaultTableModel(
                    new Object[]{"Name", "SVG Path Data", "Placement"}, 0) {
                    @Override
                    public boolean isCellEditable(int row, int col) {
                        return true;
                    }
                };

                table = new javax.swing.JTable(model);
                table.setRowHeight(22);
                table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
                // Placement column: dropdown Box / Directed
                table.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer());
                String[] placements = {"Box", "Directed"};
                javax.swing.JComboBox<String> placeCombo = new javax.swing.JComboBox<>(placements);
                table.getColumnModel().getColumn(2).setCellEditor(new javax.swing.DefaultCellEditor(placeCombo));

                javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(table);
                scroll.setPreferredSize(new java.awt.Dimension(400, 120));
                controls.add(scroll, BorderLayout.CENTER);

                JPanel buttonPanel = new JPanel();
                JButton addBtn = new JButton("Add Shape");
                JButton removeBtn = new JButton("Remove Shape");
                JButton importBtn = new JButton("Import from file...");
                importBtn.setToolTipText("Import rows from a Name|path text file, or flatten real .svg files (path/rect/circle/ellipse/polygon/groups/transforms)");
                addBtn.addActionListener(e -> {
                    model.addRow(new Object[]{"", "", "Box"});
                });
                removeBtn.addActionListener(e -> {
                    int sel = table.getSelectedRow();
                    if (sel >= 0 && sel < model.getRowCount()) {
                        model.removeRow(sel);
                    }
                });
                importBtn.addActionListener(e -> importFromFile());
                buttonPanel.add(addBtn);
                buttonPanel.add(removeBtn);
                buttonPanel.add(importBtn);
                controls.add(buttonPanel, BorderLayout.SOUTH);

                // Populate from current value
                model.setRowCount(0);
                for (String[] row : rows) {
                    model.addRow(new Object[]{row[0], row[1], "D".equals(row[2]) ? "Directed" : "Box"});
                }

                // Fire update when table changes
                model.addTableModelListener(new javax.swing.event.TableModelListener() {
                    @Override
                    public void tableChanged(javax.swing.event.TableModelEvent e) {
                        // Sync rows list from model
                        rows.clear();
                        for (int i = 0; i < model.getRowCount(); i++) {
                            String n = (String) model.getValueAt(i, 0);
                            String p = (String) model.getValueAt(i, 1);
                            String pl = model.getValueAt(i, 2) instanceof String ? (String) model.getValueAt(i, 2) : "Box";
                            if (n == null) n = "";
                            if (p == null) p = "";
                            rows.add(new String[]{n, p, "Directed".equals(pl) ? "D" : ""});
                        }
                        setValue((Object) encodeRows());
                    }
                });
            }
            return controls;
        }

        /**
         * Import shapes from one or more files:
         *  - .txt / .path / .csv: lines of "Name|pathData" (optionally "Name|path|D"),
         *    e.g. produced by the external svg_to_path.py converter.
         *  - .svg: real SVG files, flattened natively by SvgFlattener into
         *    absolute M/L/C/Z path data. Shape name defaults to the filename.
         */
        private void importFromFile() {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setMultiSelectionEnabled(true);
            fc.setDialogTitle("Import Custom Shapes (SVG path text or real .svg files)");
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "SVG / path text (svg, txt, path, csv)", "svg", "txt", "path", "csv"));
            if (fc.showOpenDialog(controls) != javax.swing.JFileChooser.APPROVE_OPTION) return;

            java.util.List<String[]> imported = new java.util.ArrayList<>();
            int failed = 0;
            for (java.io.File f : fc.getSelectedFiles()) {
                String lower = f.getName().toLowerCase(Locale.US);
                try {
                    if (lower.endsWith(".svg")) {
                        String data = SvgFlattener.flatten(f);
                        if (data == null || data.trim().isEmpty()) {
                            failed++;
                            continue;
                        }
                        String name = f.getName();
                        int dot = name.lastIndexOf('.');
                        if (dot > 0) name = name.substring(0, dot);
                        imported.add(new String[]{name, data.trim(), ""});
                    } else {
                        for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            int sep = line.indexOf('|');
                            if (sep < 0) continue;
                            String name = line.substring(0, sep).trim();
                            String rest = line.substring(sep + 1).trim();
                            String placement = "";
                            if (rest.endsWith("|D")) { placement = "D"; rest = rest.substring(0, rest.length() - 2).trim(); }
                            if (!name.isEmpty() && !rest.isEmpty())
                                imported.add(new String[]{name, rest, placement});
                        }
                    }
                } catch (Exception ex) {
                    failed++;
                    System.err.println("MapAnnotator: import failed for " + f.getName() + ": " + ex);
                }
            }

            if (imported.isEmpty()) {
                JOptionPane.showMessageDialog(controls,
                    "No convertible shapes found in the selected file(s).",
                    "Import Custom Shapes", JOptionPane.WARNING_MESSAGE);
                return;
            }
            for (String[] row : imported) {
                model.addRow(new Object[]{row[0], row[1], "D".equals(row[2]) ? "Directed" : "Box"});
            }
            String msg = "Imported " + imported.size() + " shape(s)" + (failed > 0 ? " (" + failed + " file(s) failed)" : "") + ".";
            JOptionPane.showMessageDialog(controls, msg, "Import Custom Shapes", JOptionPane.INFORMATION_MESSAGE);
        }

        private void parseRows(String s) {
            rows.clear();
            if (s == null || s.trim().isEmpty()) return;
            for (String def : s.split("\\|\\|")) {
                if (def == null || def.trim().isEmpty()) continue;
                int sep = def.indexOf('|');
                if (sep < 0) {
                    rows.add(new String[]{def.trim(), "", ""});
                } else {
                    String name = def.substring(0, sep).trim();
                    String rest = def.substring(sep + 1).trim();
                    String placement = "";
                    if (rest.endsWith("|D")) { placement = "D"; rest = rest.substring(0, rest.length() - 2).trim(); }
                    rows.add(new String[]{name, rest, placement});
                }
            }
        }

        private String encodeRows() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                String n = rows.get(i)[0];
                String p = rows.get(i)[1];
                String d = rows.get(i).length > 2 ? rows.get(i)[2] : "";
                if (n == null) n = "";
                if (p == null) p = "";
                if (n.isEmpty() && p.isEmpty()) continue;
                if (sb.length() > 0) sb.append("||");
                sb.append(n).append("|").append(p);
                if ("D".equals(d)) sb.append("|D");
            }
            return sb.toString();
        }
    }
}