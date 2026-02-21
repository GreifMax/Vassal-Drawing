package drawing;

import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

public class MapAnnotator extends AbstractConfigurable implements Drawable, GameComponent, CommandEncoder, MouseListener, MouseMotionListener {

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

    // Runtime state (SVG Memory Model)
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
    private transient Point cursorLocal = null;
    private transient ArrayList<SvgPath> previewPaths = null;
    private transient ArrayList<TextItem> previewTexts = null;

    // Toolbar
    private JToggleButton btnDraw, btnText, btnShapes, btnGum;
    private JButton btnClear;

    // ------------------- SVG Data Structures -------------------
    private static class SvgPath {
        String id;
        ArrayList<ArrayList<Point>> subpaths = new ArrayList<>();
        int rgb; double w;

        SvgPath(String id, int rgb, double w) { this.id = id; this.rgb = rgb; this.w = w; }
        SvgPath(String id, int rgb, double w, String svgData) {
            this.id = id; this.rgb = rgb; this.w = w;
            parseSvg(svgData);
        }

        void parseSvg(String data) {
            subpaths.clear();
            if (data == null || data.isEmpty()) return;
            String[] t = data.split(" ");
            ArrayList<Point> cur = null;
            int i = 0;
            while(i < t.length) {
                String cmd = t[i++];
                if (cmd.equals("M") && i+1 < t.length) {
                    cur = new ArrayList<>(); subpaths.add(cur);
                    cur.add(new Point((int)Double.parseDouble(t[i++]), (int)Double.parseDouble(t[i++])));
                } else if (cmd.equals("L") && cur != null && i+1 < t.length) {
                    cur.add(new Point((int)Double.parseDouble(t[i++]), (int)Double.parseDouble(t[i++])));
                }
            }
        }

        String toSvgData() {
            StringBuilder sb = new StringBuilder();
            for (ArrayList<Point> sub : subpaths) {
                if (sub.isEmpty()) continue;
                sb.append("M ").append(sub.get(0).x).append(" ").append(sub.get(0).y).append(" ");
                for (int i=1; i<sub.size(); i++) sb.append("L ").append(sub.get(i).x).append(" ").append(sub.get(i).y).append(" ");
            }
            return sb.toString().trim();
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
            map.removeLocalMouseListener(this);
            map.getComponent().removeMouseMotionListener(this);
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
            try { map.getClass().getMethod("addLocalMouseListenerFirst", MouseListener.class).invoke(map, this); }
            catch (Exception e) { map.addLocalMouseListener(this); }
            map.getComponent().addMouseMotionListener(this);
            GameModule.getGameModule().addCommandEncoder(this);
            GameModule.getGameModule().getGameState().addGameComponent(this);
            setupToolbar();
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
            if (JOptionPane.showConfirmDialog(map.getComponent(), "Clear all drawings on this map?", "Clear", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                Command c = new AnnotateCommand(map.getId(), "CLEAR_ALL", "");
                c.execute(); GameModule.getGameModule().sendAndLog(c);
            }
        });

        tb.add(btnDraw); tb.add(btnText); tb.add(btnShapes); tb.add(btnGum); tb.add(btnClear);
        bindHotkey(hkDraw, btnDraw); bindHotkey(hkText, btnText);
        bindHotkey(hkShapes, btnShapes); bindHotkey(hkGum, btnGum); bindHotkey(hkClear, btnClear);
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

        miArrow.addActionListener(shapeSelect); miRect.addActionListener(shapeSelect); miOval.addActionListener(shapeSelect);
        shapeMenu.add(miArrow); shapeMenu.add(miRect); shapeMenu.add(miOval);

        btnShapes.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { if (SwingUtilities.isRightMouseButton(e)) shapeMenu.show(btnShapes, e.getX(), e.getY()); }
        });
        btnShapes.addActionListener(e -> handleToggle(btnShapes, Mode.SHAPE));
    }

    private void bindHotkey(NamedKeyStroke nks, AbstractButton btn) {
        if (nks == null || nks.getKeyStroke() == null) return;
        KeyStroke ks = nks.getKeyStroke();
        JComponent jcomp = (JComponent) map.getComponent();
        String actionName = "DrawHotkey_" + UUID.randomUUID().toString();
        jcomp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, actionName);
        jcomp.getActionMap().put(actionName, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { btn.doClick(); } });
    }

    private void handleToggle(JToggleButton clickedBtn, Mode targetMode) {
        if (clickedBtn.isSelected()) {
            if (clickedBtn != btnDraw) btnDraw.setSelected(false);
            if (clickedBtn != btnText) btnText.setSelected(false);
            if (clickedBtn != btnShapes) btnShapes.setSelected(false);
            if (clickedBtn != btnGum)  btnGum.setSelected(false);
            setMode(targetMode);
        } else { setMode(Mode.OFF); }
    }

    private void setMode(Mode m) {
        this.mode = m; dragging = false; inProgressPoints = null; eraserPath = null; shapeStart = null;
        if (map != null) map.repaint();
    }

    // ------------------- Mouse Listener -------------------
    @Override
    public void mousePressed(MouseEvent e) {
        if (mode == Mode.OFF || map == null) return;
        Point local = map.componentToMap(e.getPoint());

        if (isLeftDown(e)) {
            if (mode == Mode.DRAW) {
                dragging = true; inProgressPoints = new ArrayList<>(); inProgressPoints.add(local); e.consume();
            } else if (mode == Mode.SHAPE) {
                dragging = true; shapeStart = local; cursorLocal = local; previewPaths = new ArrayList<>(); e.consume();
            } else if (mode == Mode.GUM) {
                dragging = true; eraserPath = new ArrayList<>(); eraserPath.add(local);
                previewPaths = deepCopyPaths(paths); previewTexts = deepCopyTexts(texts); e.consume();
            } else if (mode == Mode.TEXT) {
                handleTextClick(local); e.consume();
            }
            map.repaint();
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (mode == Mode.OFF || map == null) return;
        Point local = map.componentToMap(e.getPoint());
        cursorLocal = local;

        if (dragging) {
            if (mode == Mode.DRAW && inProgressPoints != null) {
                addIfFar(inProgressPoints, local, 2); e.consume();
            } else if (mode == Mode.SHAPE && shapeStart != null) {
                previewPaths = new ArrayList<>();
                previewPaths.add(createPureShapePath(shapeStart, local, currentShape, new Color(drawR, drawG, drawB).getRGB(), drawWidth));
                e.consume();
            } else if (mode == Mode.GUM && eraserPath != null) {
                addIfFar(eraserPath, local, 2);
                previewPaths = deepCopyPaths(paths); previewTexts = deepCopyTexts(texts);
                eraseByGeometricClipping(previewPaths, previewTexts, eraserPath, eraserRadius);
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
            sp.subpaths.add(inProgressPoints);
            Command c = new AnnotateCommand(map.getId(), "ADD_PATH", sp.id + ";" + sp.rgb + ";" + sp.w + ";" + sp.toSvgData());
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        } else if (mode == Mode.SHAPE && shapeStart != null && previewPaths != null && !previewPaths.isEmpty()) {
            SvgPath sp = previewPaths.get(0);
            Command c = new AnnotateCommand(map.getId(), "ADD_PATH", sp.id + ";" + sp.rgb + ";" + sp.w + ";" + sp.toSvgData());
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        } else if (mode == Mode.GUM && eraserPath != null && !eraserPath.isEmpty()) {
            StringBuilder epStr = new StringBuilder(); epStr.append(eraserRadius).append(";");
            for (Point p : eraserPath) epStr.append(p.x).append(",").append(p.y).append(";");
            Command c = new AnnotateCommand(map.getId(), "ERASE_PATH", epStr.toString());
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }

        inProgressPoints = null; eraserPath = null; shapeStart = null;
        previewPaths = null; previewTexts = null;
        map.repaint(); e.consume();
    }

    @Override public void mouseMoved(MouseEvent e) {
        if (map == null) return;
        cursorLocal = map.componentToMap(e.getPoint());
        if (mode == Mode.GUM) map.repaint();
    }
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    private void handleTextClick(Point local) {
        int idx = findTextHit(local);
        if (idx >= 0) {
            TextItem t = texts.get(idx);
            String newText = JOptionPane.showInputDialog(map.getComponent(), "Edit text:", t.text);
            if (newText == null) return;
            if (newText.trim().isEmpty()) {
                Command c = new AnnotateCommand(map.getId(), "REMOVE_TEXT", t.id);
                c.execute(); GameModule.getGameModule().sendAndLog(c);
            } else {
                String payload = t.id + ";" + t.x + ";" + t.y + ";" + new Color(textR, textG, textB).getRGB() + ";" + safeFont(fontName) + ";" + fontSize + ";" + b64(newText.trim());
                Command c = new AnnotateCommand(map.getId(), "ADD_TEXT", payload);
                c.execute(); GameModule.getGameModule().sendAndLog(c);
            }
        } else {
            String txt = JOptionPane.showInputDialog(map.getComponent(), "Text:");
            if (txt == null || txt.trim().isEmpty()) return;
            FontMetrics fm = map.getComponent().getFontMetrics(new Font(safeFont(fontName), Font.PLAIN, fontSize));
            int baselineY = local.y - (fm.getHeight() / 2) + fm.getAscent();
            String payload = UUID.randomUUID().toString() + ";" + local.x + ";" + baselineY + ";" + new Color(textR, textG, textB).getRGB() + ";" + safeFont(fontName) + ";" + fontSize + ";" + b64(txt.trim());
            Command c = new AnnotateCommand(map.getId(), "ADD_TEXT", payload);
            c.execute(); GameModule.getGameModule().sendAndLog(c);
        }
    }

    // ------------------- SVG Pure Vector Shapes (No Interpolation) -------------------
    private SvgPath createPureShapePath(Point p1, Point p2, ShapeType type, int rgb, double w) {
        SvgPath sp = new SvgPath(UUID.randomUUID().toString(), rgb, w);

        if (type == ShapeType.ARROW) {
            ArrayList<Point> line = new ArrayList<>(); line.add(p1); line.add(p2); sp.subpaths.add(line);
            double angle = Math.atan2(p2.y - p1.y, p2.x - p1.x);
            int head = 20;
            ArrayList<Point> h1 = new ArrayList<>(); h1.add(p2); h1.add(new Point((int)(p2.x - head*Math.cos(angle - Math.PI/6)), (int)(p2.y - head*Math.sin(angle - Math.PI/6))));
            ArrayList<Point> h2 = new ArrayList<>(); h2.add(p2); h2.add(new Point((int)(p2.x - head*Math.cos(angle + Math.PI/6)), (int)(p2.y - head*Math.sin(angle + Math.PI/6))));
            sp.subpaths.add(h1); sp.subpaths.add(h2);
        } else if (type == ShapeType.RECTANGLE) {
            ArrayList<Point> box = new ArrayList<>();
            int x = Math.min(p1.x, p2.x), y = Math.min(p1.y, p2.y), rw = Math.abs(p1.x - p2.x), rh = Math.abs(p1.y - p2.y);
            box.add(new Point(x, y)); box.add(new Point(x+rw, y)); box.add(new Point(x+rw, y+rh)); box.add(new Point(x, y+rh)); box.add(new Point(x, y));
            sp.subpaths.add(box);
        } else if (type == ShapeType.ELLIPSE) {
            ArrayList<Point> oval = new ArrayList<>();
            int x = Math.min(p1.x, p2.x), y = Math.min(p1.y, p2.y), rw = Math.abs(p1.x - p2.x), rh = Math.abs(p1.y - p2.y);
            int cx = x + rw/2, cy = y + rh/2, rx = rw/2, ry = rh/2;
            double circum = 2 * Math.PI * Math.max(rx, ry);
            int steps = Math.max(32, (int)(circum / 5.0)); // scale points for curve smoothness only
            for(int i=0; i<=steps; i++) oval.add(new Point((int)(cx + rx*Math.cos(2*Math.PI*i/steps)), (int)(cy + ry*Math.sin(2*Math.PI*i/steps))));
            sp.subpaths.add(oval);
        }
        return sp;
    }

    // ------------------- Network Sync & State (SVG) -------------------
    @Override public Command decode(String command) { return command.startsWith(COMMAND_PREFIX) ? new AnnotateCommand(command.substring(COMMAND_PREFIX.length())) : null; }
    @Override public String encode(Command c) { return c instanceof AnnotateCommand ? COMMAND_PREFIX + ((AnnotateCommand) c).payload : null; }
    @Override public void setup(boolean gameStarting) { if (!gameStarting) { paths.clear(); texts.clear(); if (map != null) map.repaint(); } }
    @Override public Command getRestoreCommand() { return new AnnotateCommand(map.getId(), "SET_STATE", encodeState()); }

    private class AnnotateCommand extends Command {
        String payload;
        AnnotateCommand(String mapId, String action, String data) { this.payload = mapId + "||" + action + "||" + data; }
        AnnotateCommand(String fullPayload) { this.payload = fullPayload; }

        @Override
        protected void executeCommand() {
            if (map == null) return;
            String[] parts = payload.split("\\|\\|", 3);
            if (parts.length < 3 || !parts[0].equals(map.getId())) return;
            String action = parts[1], data = parts[2];

            if (action.equals("ADD_PATH")) {
                String[] p = data.split(";", 4);
                paths.removeIf(x -> x.id.equals(p[0]));
                paths.add(new SvgPath(p[0], Integer.parseInt(p[1]), Double.parseDouble(p[2]), p.length > 3 ? p[3] : ""));
            } else if (action.equals("ADD_TEXT")) {
                String[] t = data.split(";", 7);
                texts.removeIf(x -> x.id.equals(t[0]));
                texts.add(new TextItem(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]), t[4], Integer.parseInt(t[5]), unb64(t[6])));
            } else if (action.equals("REMOVE_TEXT")) {
                texts.removeIf(t -> t.id.equals(data));
            } else if (action.equals("ERASE_PATH")) {
                String[] eData = data.split(";");
                int radius = Integer.parseInt(eData[0]);
                ArrayList<Point> ep = new ArrayList<>();
                for (int i=1; i<eData.length; i++) ep.add(new Point(Integer.parseInt(eData[i].split(",")[0]), Integer.parseInt(eData[i].split(",")[1])));
                eraseByGeometricClipping(paths, texts, ep, radius);
            } else if (action.equals("CLEAR_ALL")) { paths.clear(); texts.clear(); }
            else if (action.equals("SET_STATE")) { decodeState(data); }
            map.repaint();
        }
        @Override protected Command myUndoCommand() { return null; }
    }

    private String encodeState() {
        ArrayList<String> items = new ArrayList<>();
        for (SvgPath p : paths) { String d = p.toSvgData(); if (!d.isEmpty()) items.add("P;" + p.id + ";" + p.rgb + ";" + p.w + ";" + d); }
        for (TextItem t : texts) items.add("T;" + t.id + ";" + t.x + ";" + t.y + ";" + t.rgb + ";" + t.fontName + ";" + t.fontSize + ";" + b64(t.text));
        return String.join("@@", items);
    }

    private void decodeState(String data) {
        paths.clear(); texts.clear();
        if (data.isEmpty()) return;
        for (String item : data.split("@@")) {
            String[] parts = item.split(";", 2);
            if (parts[0].equals("P")) {
                String[] p = parts[1].split(";", 4);
                paths.add(new SvgPath(p[0], Integer.parseInt(p[1]), Double.parseDouble(p[2]), p.length > 3 ? p[3] : ""));
            } else if (parts[0].equals("T")) {
                String[] t = parts[1].split(";", 7);
                texts.add(new TextItem(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]), t[4], Integer.parseInt(t[5]), unb64(t[6])));
            }
        }
    }

    // ------------------- Drawing -------------------
    @Override public boolean drawAboveCounters() { return true; }

    @Override
    public void draw(Graphics g, Map map) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double zoom = map.getZoom();

        ArrayList<SvgPath> pToDraw = (dragging && mode == Mode.GUM && previewPaths != null) ? previewPaths : paths;
        ArrayList<TextItem> tToDraw = (dragging && mode == Mode.GUM && previewTexts != null) ? previewTexts : texts;

        for (SvgPath sp : pToDraw) {
            g2d.setColor(new Color(sp.rgb, true));
            g2d.setStroke(new BasicStroke((float)(sp.w * zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (ArrayList<Point> sub : sp.subpaths) {
                for (int i=1; i<sub.size(); i++) g2d.drawLine((int)(sub.get(i-1).x * zoom), (int)(sub.get(i-1).y * zoom), (int)(sub.get(i).x * zoom), (int)(sub.get(i).y * zoom));
            }
        }

        if (dragging && mode == Mode.SHAPE && previewPaths != null) {
            for (SvgPath sp : previewPaths) {
                g2d.setColor(new Color(sp.rgb, true));
                g2d.setStroke(new BasicStroke((float)(sp.w * zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (ArrayList<Point> sub : sp.subpaths) {
                    for (int i=1; i<sub.size(); i++) g2d.drawLine((int)(sub.get(i-1).x * zoom), (int)(sub.get(i-1).y * zoom), (int)(sub.get(i).x * zoom), (int)(sub.get(i).y * zoom));
                }
            }
        }

        if (mode == Mode.DRAW && inProgressPoints != null && inProgressPoints.size() > 1) {
            g2d.setColor(new Color(drawR, drawG, drawB));
            g2d.setStroke(new BasicStroke((float)(drawWidth * zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i=1; i<inProgressPoints.size(); i++) g2d.drawLine((int)(inProgressPoints.get(i-1).x * zoom), (int)(inProgressPoints.get(i-1).y * zoom), (int)(inProgressPoints.get(i).x * zoom), (int)(inProgressPoints.get(i).y * zoom));
        }

        for (TextItem t : tToDraw) {
            g2d.setColor(new Color(t.rgb, true));
            g2d.setFont(new Font(t.fontName, Font.PLAIN, Math.max(6, (int) Math.round(t.fontSize * zoom))));
            g2d.drawString(t.text, (int)(t.x * zoom), (int)(t.y * zoom));
        }

        if (mode == Mode.GUM && cursorLocal != null) {
            int rr = (int)(eraserRadius * zoom), cx = (int)(cursorLocal.x * zoom), cy = (int)(cursorLocal.y * zoom);
            g2d.setStroke(new BasicStroke(1.0f)); g2d.setColor(new Color(0, 0, 0, 80)); g2d.drawOval(cx - rr, cy - rr, 2*rr, 2*rr);
            g2d.setColor(new Color(255, 255, 255, 60)); g2d.drawOval(cx - rr - 1, cy - rr - 1, 2*rr + 2, 2*rr + 2);
        }
        g2d.dispose();
    }

    // ------------------- Ray-Circle Clipping Math -------------------
    private static class Segment {
        Point a, b;
        Segment(Point a, Point b) { this.a = a; this.b = b; }
    }

    private void eraseByGeometricClipping(ArrayList<SvgPath> pList, ArrayList<TextItem> tList, ArrayList<Point> rawEpList, int radius) {
        if (radius <= 0) return;

        // 1. Densely interpolate the eraser path to catch fast mouse movements
        ArrayList<Point> denseEpList = new ArrayList<>();
        for (int i = 0; i < rawEpList.size(); i++) {
            if (i == 0) denseEpList.add(rawEpList.get(i));
            else {
                Point last = rawEpList.get(i-1), cur = rawEpList.get(i);
                double d = Math.sqrt(dist2(last, cur));
                int steps = Math.max(1, (int)(d / 2.0));
                for (int j = 1; j <= steps; j++) denseEpList.add(new Point(last.x + (cur.x - last.x)*j/steps, last.y + (cur.y - last.y)*j/steps));
            }
        }

        if (pList != null) {
            for (SvgPath sp : pList) {
                ArrayList<ArrayList<Point>> newSubpaths = new ArrayList<>();
                for (ArrayList<Point> sub : sp.subpaths) {
                    if (sub.size() < 2) continue;
                    List<Segment> activeSegments = new ArrayList<>();
                    for (int i = 0; i < sub.size() - 1; i++) activeSegments.add(new Segment(sub.get(i), sub.get(i+1)));

                    // 2. Cut segments mathematically with all circles
                    for (Point ep : denseEpList) {
                        List<Segment> nextGen = new ArrayList<>();
                        for (Segment s : activeSegments) nextGen.addAll(cutSegmentWithCircle(s, ep, radius));
                        activeSegments = nextGen;
                    }

                    // 3. Re-assemble remaining fragments into continuous subpaths
                    if (activeSegments.isEmpty()) continue;
                    ArrayList<Point> cur = new ArrayList<>();
                    cur.add(activeSegments.get(0).a); cur.add(activeSegments.get(0).b);
                    for (int i = 1; i < activeSegments.size(); i++) {
                        Segment s = activeSegments.get(i);
                        if (s.a.equals(cur.get(cur.size() - 1))) {
                            cur.add(s.b);
                        } else {
                            newSubpaths.add(cur);
                            cur = new ArrayList<>();
                            cur.add(s.a); cur.add(s.b);
                        }
                    }
                    newSubpaths.add(cur);
                }
                sp.subpaths = newSubpaths;
            }
        }

        if (tList != null) {
            for (Point ep : denseEpList) {
                tList.removeIf(t -> {
                    FontMetrics fm = map.getComponent().getFontMetrics(new Font(t.fontName, Font.PLAIN, t.fontSize));
                    Rectangle box = new Rectangle(t.x, t.y - fm.getAscent(), Math.max(1, fm.stringWidth(t.text)), Math.max(1, fm.getHeight()));
                    Rectangle exp = new Rectangle(box.x - radius, box.y - radius, box.width + 2*radius, box.height + 2*radius);
                    if (!exp.contains(ep)) return false;
                    int cx = Math.max(box.x, Math.min(box.x + box.width, ep.x)), cy = Math.max(box.y, Math.min(box.y + box.height, ep.y));
                    return dist2(ep, new Point(cx, cy)) <= radius*radius;
                });
            }
        }
    }

    private List<Segment> cutSegmentWithCircle(Segment seg, Point c, int r) {
        List<Segment> res = new ArrayList<>();
        double ax = seg.a.x, ay = seg.a.y, bx = seg.b.x, by = seg.b.y;
        double cx = c.x, cy = c.y, dx = bx - ax, dy = by - ay, fx = ax - cx, fy = ay - cy;

        double A = dx*dx + dy*dy, B = 2 * (fx*dx + fy*dy), C = fx*fx + fy*fy - r*r;
        if (A == 0) { if (C > 0) res.add(seg); return res; }

        double det = B*B - 4*A*C;
        List<Double> ts = new ArrayList<>(); ts.add(0.0);
        if (det >= 0) {
            double sqrtDet = Math.sqrt(det);
            double t1 = (-B - sqrtDet) / (2*A), t2 = (-B + sqrtDet) / (2*A);
            if (t1 > 0 && t1 < 1) ts.add(t1);
            if (t2 > 0 && t2 < 1) ts.add(t2);
        }
        ts.add(1.0);

        for (int i = 0; i < ts.size() - 1; i++) {
            double tStart = ts.get(i), tEnd = ts.get(i+1);
            if (tEnd <= tStart) continue;

            double tMid = (tStart + tEnd) / 2.0;
            double mx = ax + tMid * dx, my = ay + tMid * dy;

            if ((mx - cx)*(mx - cx) + (my - cy)*(my - cy) > r*r) {
                Point pStart = new Point((int)Math.round(ax + tStart*dx), (int)Math.round(ay + tStart*dy));
                Point pEnd = new Point((int)Math.round(ax + tEnd*dx), (int)Math.round(ay + tEnd*dy));
                if (!pStart.equals(pEnd)) res.add(new Segment(pStart, pEnd));
            }
        }
        return res;
    }

    private int findTextHit(Point local) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            TextItem t = texts.get(i);
            FontMetrics fm = map.getComponent().getFontMetrics(new Font(t.fontName, Font.PLAIN, t.fontSize));
            if (new Rectangle(t.x, t.y - fm.getAscent(), Math.max(1, fm.stringWidth(t.text)), Math.max(1, fm.getHeight())).contains(local)) return i;
        }
        return -1;
    }

    private void addIfFar(ArrayList<Point> pts, Point p, int minDist) { if (pts.isEmpty() || dist2(pts.get(pts.size() - 1), p) >= minDist*minDist) pts.add(p); }
    private int dist2(Point a, Point b) { int dx = a.x - b.x, dy = a.y - b.y; return dx*dx + dy*dy; }
    private boolean isLeftDown(MouseEvent e) { return (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0; }
    private ArrayList<SvgPath> deepCopyPaths(ArrayList<SvgPath> src) {
        ArrayList<SvgPath> out = new ArrayList<>();
        for (SvgPath sp : src) out.add(new SvgPath(sp.id, sp.rgb, sp.w, sp.toSvgData()));
        return out;
    }
    private ArrayList<TextItem> deepCopyTexts(ArrayList<TextItem> src) {
        ArrayList<TextItem> out = new ArrayList<>();
        for (TextItem t : src) out.add(new TextItem(t.id, t.x, t.y, t.rgb, t.fontName, t.fontSize, t.text));
        return out;
    }
    private static String safeFont(String s) { return (s == null || s.trim().isEmpty()) ? "SansSerif" : s.trim(); }
    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String unb64(String s) { try { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); } catch(Exception e) { return ""; } }
}
