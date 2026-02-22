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

import VASSAL.build.AbstractConfigurable;
import VASSAL.build.Buildable;
import VASSAL.build.GameModule;
import VASSAL.build.module.GameComponent;
import VASSAL.build.module.Map;
import VASSAL.build.module.documentation.HelpFile;
import VASSAL.build.module.map.Drawable;
import VASSAL.command.Command;
import VASSAL.command.CommandEncoder;
import VASSAL.configure.NamedHotKeyConfigurer;
import VASSAL.tools.NamedKeyStroke;

public class MapAnnotator extends AbstractConfigurable
        implements Drawable, GameComponent, CommandEncoder, MouseListener, MouseMotionListener {

    public static final String ID = "MapAnnotator";
    public static final String COMMAND_PREFIX = "ANNOTATE;";

    // Editor properties - Visuals
    private int drawR = 255, drawG = 0, drawB = 0;
    private double drawWidth = 3.0;
    private int eraserRadius = 14;
    private String fontName = "SansSerif";
    private int fontSize = 20;
    private int textR = 255, textG = 0, textB = 0;

    // Editor properties - UI & Hotkeys
    private String btnDrawText = "Draw", btnTextText = "Text", btnShapesText = "Shapes ▼", btnGumText = "Gum", btnClearText = "Clear";
    private NamedKeyStroke hkDraw, hkText, hkShapes, hkGum, hkClear;

    // Runtime state (ALWAYS MAP COORDINATES)
    private final ArrayList<SvgPath> paths = new ArrayList<>();
    private final ArrayList<TextItem> texts = new ArrayList<>();

    // UI State
    private enum Mode { OFF, DRAW, TEXT, GUM, SHAPE }
    private enum ShapeType { RECTANGLE, ELLIPSE, ARROW }
    private Mode mode = Mode.OFF;
    private ShapeType currentShape = ShapeType.ARROW;
    private Map map;

    // Transient drawing state
    private transient boolean dragging = false;
    private transient ArrayList<Point> inProgressPoints = null;
    private transient ArrayList<Point> eraserPath = null;
    private transient Point shapeStart = null;
    private transient Point cursorMap = null;
    private transient ArrayList<SvgPath> previewPaths = null;
    private transient ArrayList<TextItem> previewTexts = null;

    // Gum incremental preview worker
    private transient int gumAppliedIdx = 0;
    private transient boolean gumWorkScheduled = false;
    private int gumWorkBudgetMs = 6;

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

    // ------------------- SVG Data Structures -------------------
    private static class SvgPath {
        String id;
        int rgb;
        double w;

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
                bounds = new Rectangle(x, y, Math.max(0, w), Math.max(0, h));
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
        String id, fontName, text;
        int x, y, rgb, fontSize;
        TextItem(String id, int x, int y, int rgb, String fontName, int fontSize, String text) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.x = x; this.y = y; this.rgb = rgb;
            this.fontName = fontName; this.fontSize = fontSize; this.text = text;
        }
    }

    // ------------------- VASSAL Component Wiring -------------------
    @Override public String getConfigureName() { return "Drawing Annotator"; }
    @Override public Class<?>[] getAllowableConfigureComponents() { return new Class<?>[0]; }
    @Override public HelpFile getHelpFile() { return null; }

    @Override
    public String[] getAttributeNames() {
        return new String[] {
                "drawR", "drawG", "drawB", "lineWidth", "eraserRadius", "fontName", "fontSize", "textR", "textG", "textB",
                "btnDrawText", "btnTextText", "btnShapesText", "btnGumText", "btnClearText",
                "hkDraw", "hkText", "hkShapes", "hkGum", "hkClear"
        };
    }

    @Override
    public String[] getAttributeDescriptions() {
        return new String[] {
                "Draw Color R (0-255)", "Draw Color G (0-255)", "Draw Color B (0-255)", "Line Width (pixels)", "Eraser Radius (pixels)",
                "Font Name (e.g. SansSerif)", "Font Size", "Text Color R", "Text Color G", "Text Color B",
                "Draw Button Tooltip", "Text Button Tooltip", "Shapes Button Tooltip", "Gum Button Tooltip", "Clear Button Tooltip",
                "Draw Hotkey", "Text Hotkey", "Shapes Hotkey", "Gum Hotkey", "Clear Hotkey"
        };
    }

    @Override
    public Class<?>[] getAttributeTypes() {
        return new Class<?>[] {
                Integer.class, Integer.class, Integer.class, Double.class, Integer.class, String.class, Integer.class, Integer.class, Integer.class, Integer.class,
                String.class, String.class, String.class, String.class, String.class,
                NamedKeyStroke.class, NamedKeyStroke.class, NamedKeyStroke.class, NamedKeyStroke.class, NamedKeyStroke.class
        };
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (value == null) return;
        if (value instanceof NamedKeyStroke) {
            if (key.equals("hkDraw")) hkDraw = (NamedKeyStroke) value;
            else if (key.equals("hkText")) hkText = (NamedKeyStroke) value;
            else if (key.equals("hkShapes")) hkShapes = (NamedKeyStroke) value;
            else if (key.equals("hkGum")) hkGum = (NamedKeyStroke) value;
            else if (key.equals("hkClear")) hkClear = (NamedKeyStroke) value;
            return;
        }
        String v = value.toString();
        try {
            if (key.equals("drawR")) drawR = Integer.parseInt(v);
            else if (key.equals("drawG")) drawG = Integer.parseInt(v);
            else if (key.equals("drawB")) drawB = Integer.parseInt(v);
            else if (key.equals("lineWidth")) drawWidth = Double.parseDouble(v);
            else if (key.equals("eraserRadius")) eraserRadius = Integer.parseInt(v);
            else if (key.equals("fontName")) fontName = v;
            else if (key.equals("fontSize")) fontSize = Integer.parseInt(v);
            else if (key.equals("textR")) textR = Integer.parseInt(v);
            else if (key.equals("textG")) textG = Integer.parseInt(v);
            else if (key.equals("textB")) textB = Integer.parseInt(v);
            else if (key.equals("btnDrawText")) btnDrawText = v;
            else if (key.equals("btnTextText")) btnTextText = v;
            else if (key.equals("btnShapesText")) btnShapesText = v;
            else if (key.equals("btnGumText")) btnGumText = v;
            else if (key.equals("btnClearText")) btnClearText = v;
            else if (key.equals("hkDraw")) hkDraw = NamedHotKeyConfigurer.decode(v);
            else if (key.equals("hkText")) hkText = NamedHotKeyConfigurer.decode(v);
            else if (key.equals("hkShapes")) hkShapes = NamedHotKeyConfigurer.decode(v);
            else if (key.equals("hkGum")) hkGum = NamedHotKeyConfigurer.decode(v);
            else if (key.equals("hkClear")) hkClear = NamedHotKeyConfigurer.decode(v);
        } catch (NumberFormatException ignored) {}
    }

    @Override
    public String getAttributeValueString(String key) {
        if (key.equals("drawR")) return String.valueOf(drawR);
        else if (key.equals("drawG")) return String.valueOf(drawG);
        else if (key.equals("drawB")) return String.valueOf(drawB);
        else if (key.equals("lineWidth")) return String.valueOf(drawWidth);
        else if (key.equals("eraserRadius")) return String.valueOf(eraserRadius);
        else if (key.equals("fontName")) return fontName;
        else if (key.equals("fontSize")) return String.valueOf(fontSize);
        else if (key.equals("textR")) return String.valueOf(textR);
        else if (key.equals("textG")) return String.valueOf(textG);
        else if (key.equals("textB")) return String.valueOf(textB);
        else if (key.equals("btnDrawText")) return btnDrawText;
        else if (key.equals("btnTextText")) return btnTextText;
        else if (key.equals("btnShapesText")) return btnShapesText;
        else if (key.equals("btnGumText")) return btnGumText;
        else if (key.equals("btnClearText")) return btnClearText;
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
            map.popMouseListener(this);
            if (map.getView() != null) map.getView().removeMouseMotionListener(this);

            if (btnDraw != null) map.getToolBar().remove(btnDraw);
            if (btnText != null) map.getToolBar().remove(btnText);
            if (btnShapes != null) map.getToolBar().remove(btnShapes);
            if (btnGum != null) map.getToolBar().remove(btnGum);
            if (btnClear != null) map.getToolBar().remove(btnClear);
        }
        GameModule.getGameModule().removeCommandEncoder(this);
        GameModule.getGameModule().getGameState().removeGameComponent(this);
    }

    @Override
    public void addTo(Buildable parent) {
        if (parent instanceof Map) {
            this.map = (Map) parent;
            map.addDrawComponent(this);
            GameModule.getGameModule().addCommandEncoder(this);
            GameModule.getGameModule().getGameState().addGameComponent(this);
            setupToolbar();

            SwingUtilities.invokeLater(() -> {
                if (map != null && map.getView() != null) {
                    map.pushMouseListener(this);
                    map.getView().addMouseMotionListener(this);
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
        setupShapesMenu();

        btnClear.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(map.getView(), "Clear all drawings on this map?", "Clear",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                Command c = new AnnotateCommand(map.getId(), "CLEAR_ALL", "");
                c.execute(); GameModule.getGameModule().sendAndLog(c);
            }
        });

        tb.add(btnDraw); tb.add(btnText); tb.add(btnShapes); tb.add(btnGum); tb.add(btnClear);
        bindHotkey(hkDraw, btnDraw);
        bindHotkey(hkText, btnText);
        bindHotkey(hkShapes, btnShapes);
        bindHotkey(hkGum, btnGum);
        bindHotkey(hkClear, btnClear);
    }

    private void setupShapesMenu() {
        JPopupMenu shapeMenu = new JPopupMenu();
        JMenuItem miArrow = new JMenuItem("Arrow");
        JMenuItem miRect = new JMenuItem("Rectangle");
        JMenuItem miOval = new JMenuItem("Ellipse");

        ActionListener shapeSelect = e -> {
            String cmd = e.getActionCommand();
            if (cmd.equals("Arrow")) currentShape = ShapeType.ARROW;
            else if (cmd.equals("Rectangle")) currentShape = ShapeType.RECTANGLE;
            else if (cmd.equals("Ellipse")) currentShape = ShapeType.ELLIPSE;

            btnShapes.setText(cmd + " ▼");
            if (!btnShapes.isSelected()) btnShapes.doClick();
            else setMode(Mode.SHAPE);
        };

        miArrow.addActionListener(shapeSelect);
        miRect.addActionListener(shapeSelect);
        miOval.addActionListener(shapeSelect);
        shapeMenu.add(miArrow); shapeMenu.add(miRect); shapeMenu.add(miOval);

        btnShapes.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) shapeMenu.show(btnShapes, e.getX(), e.getY());
            }
        });
        btnShapes.addActionListener(e -> handleToggle(btnShapes, Mode.SHAPE));
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
        if (map != null) map.repaint();
    }

    // ------------------- Mouse Listener -------------------
    @Override
    public void mousePressed(MouseEvent e) {
        if (mode == Mode.OFF || map == null) return;

        // map.pushMouseListener delivers MAP coords
        final Point mapLoc = e.getPoint();

        if (isLeftDown(e)) {
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
                gumWorkScheduled = false;

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
                previewPaths.add(createPureShapePath(shapeStart, mapLoc, currentShape,
                        new Color(drawR, drawG, drawB).getRGB(), drawWidth));
                e.consume();
            }
            else if (mode == Mode.GUM && eraserPath != null) {
                int before = eraserPath.size();
                addIfFar(eraserPath, mapLoc, 2);
                if (eraserPath.size() != before) scheduleGumPreviewWork();
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
            SvgPath sp = new SvgPath(UUID.randomUUID().toString(), new Color(drawR, drawG, drawB).getRGB(), drawWidth);
            sp.subs.add(polylineToSubpath(inProgressPoints));
            sp.invalidateBounds();
            Command c = new AnnotateCommand(map.getId(), "ADD_PATH",
                    sp.id + ";" + sp.rgb + ";" + sp.w + ";" + sp.toSvgData());
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }
        else if (mode == Mode.SHAPE && shapeStart != null && previewPaths != null && !previewPaths.isEmpty()) {
            SvgPath sp = previewPaths.get(0);
            Command c = new AnnotateCommand(map.getId(), "ADD_PATH",
                    sp.id + ";" + sp.rgb + ";" + sp.w + ";" + sp.toSvgData());
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }
        else if (mode == Mode.GUM && eraserPath != null && !eraserPath.isEmpty()) {
            StringBuilder epStr = new StringBuilder();
            epStr.append(eraserRadius).append(";");
            for (Point p : eraserPath) epStr.append(p.x).append(",").append(p.y).append(";");
            Command c = new AnnotateCommand(map.getId(), "ERASE_PATH", epStr.toString());
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }

        gumWorkScheduled = false;

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
        if (map == null) return;
        cursorMap = map.componentToMap(e.getPoint());

        // TEXT preview follows cursor
        if (mode == Mode.TEXT || mode == Mode.GUM) map.repaint();
    }

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    // ------------------- Text dialog actions -------------------
    private void handleTextClick(Point mapLoc) {
        int idx = findTextHit(mapLoc);
        if (idx >= 0) {
            TextItem t = texts.get(idx);
            String newText = JOptionPane.showInputDialog(map.getView(), "Edit text:", t.text);
            if (newText == null) return;

            if (newText.trim().isEmpty()) {
                Command c = new AnnotateCommand(map.getId(), "REMOVE_TEXT", t.id);
                c.execute(); GameModule.getGameModule().sendAndLog(c);
            }
            else {
                String payload = t.id + ";" + t.x + ";" + t.y + ";"
                        + new Color(textR, textG, textB).getRGB() + ";"
                        + safeFont(fontName) + ";" + fontSize + ";" + b64(newText.trim());
                Command c = new AnnotateCommand(map.getId(), "ADD_TEXT", payload);
                c.execute(); GameModule.getGameModule().sendAndLog(c);
            }
        }
        else {
            String txt = JOptionPane.showInputDialog(map.getView(), "Text:");
            if (txt == null || txt.trim().isEmpty()) return;

            Font f = new Font(safeFont(fontName), Font.PLAIN, fontSize);
            FontMetrics fm = map.getView().getFontMetrics(f);
            int baselineY = mapLoc.y - (fm.getHeight() / 2) + fm.getAscent();

            String payload = UUID.randomUUID().toString() + ";" + mapLoc.x + ";" + baselineY + ";"
                    + new Color(textR, textG, textB).getRGB() + ";"
                    + safeFont(fontName) + ";" + fontSize + ";" + b64(txt.trim());
            Command c = new AnnotateCommand(map.getId(), "ADD_TEXT", payload);
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }
    }

    // ------------------- Gum Preview Worker -------------------
    private void scheduleGumPreviewWork() {
        if (gumWorkScheduled) return;
        gumWorkScheduled = true;

        SwingUtilities.invokeLater(() -> {
            gumWorkScheduled = false;
            runGumPreviewBurst();
        });
    }

    private void runGumPreviewBurst() {
        if (map == null || !dragging || mode != Mode.GUM) return;
        if (previewPaths == null || previewTexts == null || eraserPath == null) return;

        final long deadline = System.nanoTime() + gumWorkBudgetMs * 1_000_000L;

        while (gumAppliedIdx < eraserPath.size() - 1 && System.nanoTime() < deadline) {
            Point a = eraserPath.get(gumAppliedIdx);
            Point b = eraserPath.get(gumAppliedIdx + 1);

            ArrayList<Point> step = new ArrayList<>(2);
            step.add(a);
            step.add(b);

            eraseByGeometricClipping(previewPaths, previewTexts, step, eraserRadius);
            gumAppliedIdx++;
        }

        map.repaint();

        if (gumAppliedIdx < eraserPath.size() - 1) scheduleGumPreviewWork();
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
            if (map != null) map.repaint();
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
                String[] p = data.split(";", 4);
                paths.removeIf(x -> x.id.equals(p[0]));
                paths.add(new SvgPath(p[0], Integer.parseInt(p[1]), Double.parseDouble(p[2]), p.length > 3 ? p[3] : ""));
            }
            else if (action.equals("ADD_TEXT")) {
                String[] t = data.split(";", 7);
                texts.removeIf(x -> x.id.equals(t[0]));
                texts.add(new TextItem(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                        Integer.parseInt(t[3]), t[4], Integer.parseInt(t[5]), unb64(t[6])));
            }
            else if (action.equals("REMOVE_TEXT")) {
                texts.removeIf(t -> t.id.equals(data));
            }
            else if (action.equals("ERASE_PATH")) {
                String[] eData = data.split(";");
                int radius = Integer.parseInt(eData[0]);
                ArrayList<Point> ep = new ArrayList<>();
                for (int i = 1; i < eData.length; i++) {
                    if (eData[i].isEmpty()) continue;
                    String[] xy = eData[i].split(",");
                    if (xy.length != 2) continue;
                    ep.add(new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
                }
                eraseByGeometricClipping(paths, texts, ep, radius);
            }
            else if (action.equals("CLEAR_ALL")) {
                paths.clear(); texts.clear();
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
        for (SvgPath p : paths) {
            String d = p.toSvgData();
            if (!d.isEmpty()) items.add("P;" + p.id + ";" + p.rgb + ";" + p.w + ";" + d);
        }
        for (TextItem t : texts) {
            items.add("T;" + t.id + ";" + t.x + ";" + t.y + ";" + t.rgb + ";" + t.fontName + ";" + t.fontSize + ";" + b64(t.text));
        }
        return String.join("@@", items);
    }

    private void decodeState(String data) {
        paths.clear(); texts.clear();
        if (data == null || data.isEmpty()) return;

        for (String item : data.split("@@")) {
            String[] parts = item.split(";", 2);
            if (parts.length < 2) continue;

            if (parts[0].equals("P")) {
                String[] p = parts[1].split(";", 4);
                paths.add(new SvgPath(p[0], Integer.parseInt(p[1]), Double.parseDouble(p[2]), p.length > 3 ? p[3] : ""));
            }
            else if (parts[0].equals("T")) {
                String[] t = parts[1].split(";", 7);
                texts.add(new TextItem(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                        Integer.parseInt(t[3]), t[4], Integer.parseInt(t[5]), unb64(t[6])));
            }
        }
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

        // Draw committed (or gum-preview) paths
        for (SvgPath sp : pToDraw) {
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
            g2d.setColor(new Color(drawR, drawG, drawB));
            g2d.setStroke(new BasicStroke((float) (drawWidth * zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < inProgressPoints.size(); i++) {
                Point a = map.mapToComponent(inProgressPoints.get(i - 1));
                Point b = map.mapToComponent(inProgressPoints.get(i));
                g2d.drawLine(a.x, a.y, b.x, b.y);
            }
        }

        // Committed (or gum-preview) texts
        for (TextItem t : tToDraw) {
            g2d.setColor(new Color(t.rgb, true));
            int zFont = Math.max(1, (int) Math.round(t.fontSize * zoom));
            g2d.setFont(new Font(t.fontName, Font.PLAIN, zFont));
            Point p = map.mapToComponent(new Point(t.x, t.y));
            g2d.drawString(t.text, p.x, p.y);
        }

        // Text preview at cursor (draw-only)
        if (mode == Mode.TEXT && cursorMap != null) {
            int zFont = Math.max(1, (int) Math.round(fontSize * zoom));
            Font f = new Font(safeFont(fontName), Font.PLAIN, zFont);
            g2d.setFont(f);

            // baseline calculation similar to insertion baseline (centered around click)
            FontMetrics fm = map.getView().getFontMetrics(f);
            int baselineY = cursorMap.y - (fm.getHeight() / 2) + fm.getAscent();

            Point p = map.mapToComponent(new Point(cursorMap.x, baselineY));
            Color c = new Color(textR, textG, textB, TEXT_PREVIEW_ALPHA);
            g2d.setColor(c);
            g2d.drawString(TEXT_PREVIEW_SAMPLE, p.x, p.y);
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
                                          ArrayList<Point> rawEpList, int radiusMapUnits) {
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
                    FontMetrics fm = map.getView().getFontMetrics(new Font(t.fontName, Font.PLAIN, t.fontSize));
                    Rectangle box = new Rectangle(t.x, t.y - fm.getAscent(),
                            Math.max(1, fm.stringWidth(t.text)), Math.max(1, fm.getHeight()));
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
        for (int i = texts.size() - 1; i >= 0; i--) {
            TextItem t = texts.get(i);
            FontMetrics fm = map.getView().getFontMetrics(new Font(t.fontName, Font.PLAIN, t.fontSize));
            if (new Rectangle(t.x, t.y - fm.getAscent(),
                    Math.max(1, fm.stringWidth(t.text)), Math.max(1, fm.getHeight())).contains(local)) return i;
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
        for (TextItem t : src) out.add(new TextItem(t.id, t.x, t.y, t.rgb, t.fontName, t.fontSize, t.text));
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
}
