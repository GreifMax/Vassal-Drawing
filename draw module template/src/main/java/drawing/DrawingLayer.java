package drawing;

import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import javax.swing.*;
import javax.swing.border.TitledBorder;

import VASSAL.build.module.Map;
import VASSAL.build.module.documentation.HelpFile;
import VASSAL.command.ChangeTracker;
import VASSAL.command.Command;
import VASSAL.counters.Decorator;
import VASSAL.counters.GamePiece;
import VASSAL.counters.KeyCommand;
import VASSAL.counters.PieceEditor;
import VASSAL.tools.SequenceEncoder;

public class DrawingLayer extends Decorator {

  public static final String ID = "drawlayer;";

  // Mode hotkeys (also appear in right-click menu)
  private static final KeyStroke MODE_DRAW  = KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK);
  private static final KeyStroke MODE_TEXT  = KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK);
  private static final KeyStroke MODE_OFF   = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

  // -------- Type (config) fields: edited in Piece Editor, do NOT change during play --------
  private int drawR = 255, drawG = 0, drawB = 0;
  private double drawWidth = 3.0;

  private int eraserRadius = 14; // pixels in map coords (piece-local)

  private String fontName = "SansSerif";
  private int fontSize = 20;
  private int textR = 255, textG = 0, textB = 0;

  // -------- State (runtime) fields: change during play, must be serialized --------
  private final ArrayList<Stroke> strokes = new ArrayList<>();
  private final ArrayList<TextItem> texts = new ArrayList<>();

  // -------- Transient UI fields (not serialized) --------
  private enum Mode { OFF, DRAW, TEXT }
  private transient Mode mode = Mode.OFF;

  private transient boolean drawingNow = false;
  private transient ArrayList<Point> inProgressStroke = null;

  private transient boolean erasingNow = false;
  private transient ArrayList<Point> eraserPath = null;

  // Dynamic eraser preview (local-only while dragging)
  private transient ArrayList<Stroke> baseStrokes = null;
  private transient ArrayList<TextItem> baseTexts = null;
  private transient ArrayList<Stroke> previewStrokes = null;
  private transient ArrayList<TextItem> previewTexts = null;
  private transient Point lastEraserLocal = null;

  private transient DrawingMouseHandler handler = null;
  private transient boolean listenersInstalled = false;

  // Used for hit-testing / text baseline metrics
  private static final Canvas METRICS_CANVAS = new Canvas();

  // Gum usage
  private transient Point gumCursorLocal = null;
  private transient boolean showGumCursor = false;


  // ------------------- Data structures -------------------
  private static class Stroke {
    final ArrayList<Point> pts;     // piece-local coordinates (map pixels, unzoomed)
    final int rgb;
    final double w;

    Stroke(ArrayList<Point> pts, int rgb, double w) {
      this.pts = new ArrayList<>(pts);
      this.rgb = rgb;
      this.w = w;
    }
  }

  private static class TextItem {
    int x;              // piece-local coordinates (map pixels)
    int y;              // baseline y (piece-local)
    int rgb;
    String fontName;
    int fontSize;
    String text;        // UTF-8 string

    TextItem(int x, int y, int rgb, String fontName, int fontSize, String text) {
      this.x = x;
      this.y = y;
      this.rgb = rgb;
      this.fontName = fontName;
      this.fontSize = fontSize;
      this.text = text;
    }
  }

  // ------------------- Constructor -------------------
  public DrawingLayer() {
    this(ID, null);
  }

  public DrawingLayer(String type, GamePiece inner) {
    setInner(inner);
    mySetType(type);
  }

  // ------------------- Type (config) encoding -------------------
  @Override
  public void mySetType(String type) {
    String rest = type;
    if (rest != null && rest.startsWith(ID)) rest = rest.substring(ID.length());
    if (rest == null || rest.isEmpty()) return;

    SequenceEncoder.Decoder sd = new SequenceEncoder.Decoder(rest, ',');
    drawR = clamp(sd.nextInt(drawR), 0, 255);
    drawG = clamp(sd.nextInt(drawG), 0, 255);
    drawB = clamp(sd.nextInt(drawB), 0, 255);
    drawWidth = clamp(sd.nextDouble(drawWidth), 0.5, 50.0);

    eraserRadius = clamp(sd.nextInt(eraserRadius), 1, 500);

    fontName = safeFont(sd.nextToken(fontName));
    fontSize = clamp(sd.nextInt(fontSize), 6, 200);
    textR = clamp(sd.nextInt(textR), 0, 255);
    textG = clamp(sd.nextInt(textG), 0, 255);
    textB = clamp(sd.nextInt(textB), 0, 255);
  }

  @Override
  public String myGetType() {
    SequenceEncoder se = new SequenceEncoder(',');
    se.append(drawR);
    se.append(drawG);
    se.append(drawB);
    se.append(drawWidth);

    se.append(eraserRadius);

    se.append(fontName);
    se.append(fontSize);
    se.append(textR);
    se.append(textG);
    se.append(textB);

    return ID + se.getValue();
  }

  // ------------------- State (runtime) encoding -------------------
  @Override
  public String myGetState() {
    SequenceEncoder se = new SequenceEncoder(';');

    // strokes
    se.append(strokes.size());
    for (Stroke s : strokes) {
      se.append(s.rgb);
      se.append(s.w);
      se.append(s.pts.size());
      for (Point p : s.pts) {
        se.append(p.x);
        se.append(p.y);
      }
    }

    // texts
    se.append(texts.size());
    for (TextItem t : texts) {
      se.append(t.x);
      se.append(t.y);
      se.append(t.rgb);
      se.append(t.fontName);
      se.append(t.fontSize);
      se.append(b64(t.text));
    }

    return se.getValue();
  }

  @Override
  public void mySetState(String newState) {
    SequenceEncoder.Decoder sd = new SequenceEncoder.Decoder(newState, ';');

    // strokes
    int nStrokes = sd.nextInt(0);
    strokes.clear();
    for (int i = 0; i < nStrokes; i++) {
      int rgb = sd.nextInt(new Color(drawR, drawG, drawB).getRGB());
      double w = sd.nextDouble(drawWidth);
      int ptsN = sd.nextInt(0);
      ArrayList<Point> pts = new ArrayList<>();
      for (int j = 0; j < ptsN; j++) {
        pts.add(new Point(sd.nextInt(0), sd.nextInt(0)));
      }
      if (pts.size() >= 2) strokes.add(new Stroke(pts, rgb, w));
    }

    // texts
    int nTexts = sd.nextInt(0);
    texts.clear();
    for (int i = 0; i < nTexts; i++) {
      int x = sd.nextInt(0);
      int y = sd.nextInt(0);
      int rgb = sd.nextInt(new Color(textR, textG, textB).getRGB());
      String fn = safeFont(sd.nextToken(fontName));
      int fs = clamp(sd.nextInt(fontSize), 6, 200);
      String txt = unb64(sd.nextToken(""));
      if (txt != null && !txt.isEmpty()) {
        texts.add(new TextItem(x, y, rgb, fn, fs, txt));
      }
    }
  }

  // ------------------- Key commands -------------------
  @Override
  protected KeyCommand[] myGetKeyCommands() {
    return new KeyCommand[] {
            new KeyCommand(mode == Mode.DRAW ? "Drawing: ON (Ctrl+D)" : "Enter drawing mode (Ctrl+D)", MODE_DRAW, this),
            new KeyCommand(mode == Mode.TEXT ? "Text: ON (Ctrl+T)" : "Enter text mode (Ctrl+T)", MODE_TEXT, this),
            new KeyCommand("Exit mode (Esc)", MODE_OFF, this),
    };
  }

  @Override
  public Command myKeyEvent(KeyStroke ks) {
    if (MODE_DRAW.equals(ks)) { setMode(Mode.DRAW); return null; }
    if (MODE_TEXT.equals(ks)) { setMode(Mode.TEXT); return null; }
    if (MODE_OFF.equals(ks))  { setMode(Mode.OFF);  return null; }
    return null;
  }

  private void setMode(Mode newMode) {
    final Map map = getMap();
    mode = newMode;

    drawingNow = false; inProgressStroke = null;
    stopEraserPreview();

    if (map == null) return;

    if (mode != Mode.OFF) {
      if (handler == null) handler = new DrawingMouseHandler();

      if (!listenersInstalled) {
        listenersInstalled = true;
        try {
          map.getClass().getMethod("addLocalMouseListenerFirst", MouseListener.class).invoke(map, handler);
        } catch (Exception ignore) {
          map.addLocalMouseListener(handler);
        }
        map.getComponent().addMouseMotionListener(handler);
      }

      map.repaint();
    }
    else {
      if (listenersInstalled && handler != null) {
        listenersInstalled = false;
        map.removeLocalMouseListener(handler);
        map.getComponent().removeMouseMotionListener(handler);
      }
      map.repaint();
    }
  }

  // ------------------- Mouse handler -------------------
  private class DrawingMouseHandler implements MouseListener, MouseMotionListener {

    @Override
    public void mousePressed(MouseEvent e) {
      if (mode == Mode.OFF) return;

      Point mapPt = e.getPoint();       // MAP coords
      Point local = mapToLocal(mapPt);  // piece-local coords

      if (mode == Mode.DRAW) {
        if (isCtrlLeft(e)) {              // gumActive + LMB
          startEraserPreview(local);
          e.consume();
          repaintMap();
          return;
        }
        if (isLeftDown(e)) {              // normal draw
          drawingNow = true;
          inProgressStroke = new ArrayList<>();
          inProgressStroke.add(local);
          e.consume();
          repaintMap();
          return;
        }
        return;
      }

      if (mode == Mode.TEXT) {
        if (isLeftDown(e)) {
          handleTextLeftClick(local);
          e.consume();
          repaintMap();
          return;
        }
        if (isCtrlLeft(e)) {
          int idx = findTextHit(local);          // uses textBox(t).contains(local)
          if (idx >= 0) {
            ChangeTracker ct = new ChangeTracker(Decorator.getOutermost(DrawingLayer.this));
            texts.remove(idx);
            Command c = ct.getChangeCommand();
            if (c != null) sendAndLogCompat(c);
            repaintMap();
          }
          e.consume();
        }

      }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
      if (mode == Mode.OFF) return;
      if (getMap() == null) return;

      Point mapPt = getMap().componentToMap(e.getPoint()); // COMPONENT -> MAP
      Point local = mapToLocal(mapPt);

      // keep cursor circle synced while dragging
      gumCursorLocal = local;
      showGumCursor = gumActive(e);   // instead of e.isControlDown()

      if (mode == Mode.DRAW) {
        if (!gumActive(e) && drawingNow && inProgressStroke != null && (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
          addIfFar(inProgressStroke, local, 2);
          e.consume();
          repaintMap();
          return;
        }
        if (erasingNow && eraserPath != null && isCtrlLeft(e)) {
          addIfFar(eraserPath, local, 2);
          lastEraserLocal = local;
          updateEraserPreview();
          e.consume();
          repaintMap();
        }
      }
    }


    @Override
    public void mouseReleased(MouseEvent e) {
      if (mode == Mode.OFF) return;

      if (mode == Mode.DRAW) {
        if (drawingNow && inProgressStroke != null) {
          drawingNow = false;
          ArrayList<Point> done = inProgressStroke;
          inProgressStroke = null;

          if (done.size() >= 2) {
            ChangeTracker ct = new ChangeTracker(Decorator.getOutermost(DrawingLayer.this));
            strokes.add(new Stroke(done, new Color(drawR, drawG, drawB).getRGB(), drawWidth));
            Command c = ct.getChangeCommand();
            if (c != null) sendAndLogCompat(c);
          }

          e.consume();
          repaintMap();
          return;
        }

        if (erasingNow) {
          commitEraserPreview();
          e.consume();
          repaintMap();
        }
      }
    }

    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }
    @Override
    public void mouseMoved(MouseEvent e) {
      if (mode == Mode.OFF) return;
      if (getMap() == null) return;

      Point mapPt = getMap().componentToMap(e.getPoint());
      Point local = mapToLocal(mapPt);
      gumCursorLocal = local;
      showGumCursor = gumActive(e);   // instead of e.isControlDown()
      repaintMap();
    }


  }

  // ------------------- Text behavior -------------------
  private void handleTextLeftClick(Point local) {
    int idx = findTextHit(local);
    if (idx >= 0) {
      TextItem t = texts.get(idx);
      String newText = JOptionPane.showInputDialog(null, "Edit text:", t.text);
      if (newText == null) return;

      newText = newText.trim();
      if (newText.isEmpty()) {
        ChangeTracker ct = new ChangeTracker(Decorator.getOutermost(this));
        texts.remove(idx);
        Command c = ct.getChangeCommand();
        if (c != null) sendAndLogCompat(c);
        return;
      }

      ChangeTracker ct = new ChangeTracker(Decorator.getOutermost(this));
      t.text = newText;
      t.rgb = new Color(textR, textG, textB).getRGB();
      t.fontName = safeFont(fontName);
      t.fontSize = fontSize;
      Command c = ct.getChangeCommand();
      if (c != null) sendAndLogCompat(c);
      return;
    }

    String txt = JOptionPane.showInputDialog(null, "Text:");
    if (txt == null) return;
    txt = txt.trim();
    if (txt.isEmpty()) return;

    // Make the click point be the TOP of the text (Paint-like),
    // but store baseline for drawString().
    Font f = new Font(safeFont(fontName), Font.PLAIN, fontSize);
    FontMetrics fm = METRICS_CANVAS.getFontMetrics(f);
    int h = fm.getHeight();
    int baselineY = local.y + h/4;

    ChangeTracker ct = new ChangeTracker(Decorator.getOutermost(this));
    texts.add(new TextItem(
            local.x,
            baselineY,
            new Color(textR, textG, textB).getRGB(),
            safeFont(fontName),
            fontSize,
            txt
    ));
    Command c = ct.getChangeCommand();
    if (c != null) sendAndLogCompat(c);
  }

  // ------------------- Dynamic eraser preview -------------------
  private void startEraserPreview(Point local) {
    erasingNow = true;
    eraserPath = new ArrayList<>();
    eraserPath.add(local);
    lastEraserLocal = local;

    // Snapshot current state for preview
    baseStrokes = deepCopyStrokes(strokes);
    baseTexts = deepCopyTexts(texts);

    updateEraserPreview();
  }

  private void updateEraserPreview() {
    if (!erasingNow || eraserPath == null || baseStrokes == null || baseTexts == null) return;

    previewStrokes = deepCopyStrokes(baseStrokes);
    previewTexts = deepCopyTexts(baseTexts);

    eraseAlongPathOn(previewStrokes, previewTexts, eraserPath, eraserRadius);
  }

  private void commitEraserPreview() {
    if (!erasingNow) return;

    // If for some reason preview didn't build, just stop.
    if (previewStrokes == null || previewTexts == null) {
      stopEraserPreview();
      return;
    }

    ChangeTracker ct = new ChangeTracker(Decorator.getOutermost(DrawingLayer.this));

    strokes.clear();
    strokes.addAll(previewStrokes);

    texts.clear();
    texts.addAll(previewTexts);

    Command c = ct.getChangeCommand();
    if (c != null) sendAndLogCompat(c);

    stopEraserPreview();
  }

  private void stopEraserPreview() {
    erasingNow = false;
    eraserPath = null;
    baseStrokes = null;
    baseTexts = null;
    previewStrokes = null;
    previewTexts = null;
    lastEraserLocal = null;
  }

  private ArrayList<Stroke> deepCopyStrokes(ArrayList<Stroke> src) {
    ArrayList<Stroke> out = new ArrayList<>();
    for (Stroke s : src) {
      out.add(new Stroke(new ArrayList<>(s.pts), s.rgb, s.w));
    }
    return out;
  }

  private ArrayList<TextItem> deepCopyTexts(ArrayList<TextItem> src) {
    ArrayList<TextItem> out = new ArrayList<>();
    for (TextItem t : src) {
      out.add(new TextItem(t.x, t.y, t.rgb, t.fontName, t.fontSize, t.text));
    }
    return out;
  }

  // ------------------- Erasing math (works on provided lists) -------------------
  private void eraseAlongPathOn(ArrayList<Stroke> strokeList, ArrayList<TextItem> textList, ArrayList<Point> path, int radius) {
    if (radius <= 0) return;

    // Erase strokes by splitting them where points enter the gum radius
    if (strokeList != null && !strokeList.isEmpty()) {
      ArrayList<Stroke> newStrokes = new ArrayList<>();

      for (Stroke s : strokeList) {
        ArrayList<Point> current = new ArrayList<>();

        for (Point p : s.pts) {
          boolean erased = false;
          for (Point ep : path) {
            if (dist2(p, ep) <= radius * radius) { erased = true; break; }
          }

          if (!erased) {
            current.add(p);
          } else {
            if (current.size() >= 2) newStrokes.add(new Stroke(current, s.rgb, s.w));
            current = new ArrayList<>();
          }
        }

        if (current.size() >= 2) newStrokes.add(new Stroke(current, s.rgb, s.w));
      }

      strokeList.clear();
      strokeList.addAll(newStrokes);
    }

    // Erase any text touched by the gum path
    if (textList != null && !textList.isEmpty()) {
      for (Point ep : path) deleteTextsNear(textList, ep, radius);
    }
  }

  private void deleteTextsNear(ArrayList<TextItem> textList, Point local, int radius) {
    if (textList == null || textList.isEmpty()) return;
    int r2 = radius * radius;

    textList.removeIf(t -> {
      Rectangle box = textBox(t);

      Rectangle expanded = new Rectangle(
              box.x - radius, box.y - radius,
              box.width + 2 * radius, box.height + 2 * radius
      );

      if (!expanded.contains(local)) return false;

      int cx = clamp(local.x, box.x, box.x + box.width);
      int cy = clamp(local.y, box.y, box.y + box.height);
      return dist2(local, new Point(cx, cy)) <= r2;
    });
  }

  private int findTextHit(Point local) {
    for (int i = texts.size() - 1; i >= 0; i--) {
      if (textBox(texts.get(i)).contains(local)) return i;
    }
    return -1;
  }

  private Rectangle textBox(TextItem t) {
    Font f = new Font(t.fontName, Font.PLAIN, t.fontSize);
    FontMetrics fm = METRICS_CANVAS.getFontMetrics(f);
    int w = fm.stringWidth(t.text != null ? t.text : "");
    int h = fm.getHeight();
    int ascent = fm.getAscent();
    return new Rectangle(t.x, t.y - ascent, Math.max(1, w), Math.max(1, h));
  }

  // ------------------- Coords & input helpers -------------------
  private Point mapToLocal(Point mapPt) {
    Point piecePos = getPosition();
    return new Point(mapPt.x - piecePos.x, mapPt.y - piecePos.y);
  }

  private void addIfFar(ArrayList<Point> pts, Point p, int minDistPx) {
    if (pts.isEmpty()) { pts.add(p); return; }
    Point last = pts.get(pts.size() - 1);
    int d2 = dist2(last, p);
    if (d2 >= minDistPx * minDistPx) pts.add(p);
  }

  private int dist2(Point a, Point b) {
    int dx = a.x - b.x;
    int dy = a.y - b.y;
    return dx * dx + dy * dy;
  }

  private boolean isLeftDown(MouseEvent e) {
    return !e.isControlDown() && (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0;
  }

  private boolean isCtrlLeft(MouseEvent e) {
    return gumActive(e) && (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0;
  }

  private boolean gumActive(MouseEvent e) {
    if (e.isControlDown()) return true;

    Object v = Decorator.getOutermost(this).getProperty("GumLatch");
    return v != null && ("1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString()));
  }


  private void repaintMap() {
    if (getMap() != null) getMap().repaint();
  }

  // Broadcast changes made from mouse gestures (not from KeyCommand).
  private void sendAndLogCompat(Command c) {
    try {
      Class<?> gmClz = Class.forName("VASSAL.build.GameModule");
      Object gm = gmClz.getMethod("getGameModule").invoke(null);
      gmClz.getMethod("sendAndLog", Command.class).invoke(gm, c);
    }
    catch (Exception ex) {
      c.execute();
    }
  }

  // ------------------- Rendering -------------------
  @Override
  public void draw(Graphics g, int x, int y, Component obs, double zoom) {
    piece.draw(g, x, y, obs, zoom);

    Graphics2D g2d = (Graphics2D) g.create();
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    // Use preview lists while erasing (local-only), otherwise committed lists.
    ArrayList<Stroke> strokeToDraw = (erasingNow && previewStrokes != null) ? previewStrokes : strokes;
    ArrayList<TextItem> textToDraw = (erasingNow && previewTexts != null) ? previewTexts : texts;

    // committed/preview strokes
    for (Stroke s : strokeToDraw) {
      g2d.setColor(new Color(s.rgb, true));
      g2d.setStroke(new BasicStroke((float) (s.w * zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      for (int i = 1; i < s.pts.size(); i++) {
        Point p1 = s.pts.get(i - 1);
        Point p2 = s.pts.get(i);
        g2d.drawLine(
                x + (int) Math.round(p1.x * zoom), y + (int) Math.round(p1.y * zoom),
                x + (int) Math.round(p2.x * zoom), y + (int) Math.round(p2.y * zoom)
        );
      }
    }

    // in-progress draw preview
    if (inProgressStroke != null && inProgressStroke.size() > 1) {
      g2d.setColor(new Color(drawR, drawG, drawB));
      g2d.setStroke(new BasicStroke((float) (drawWidth * zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      for (int i = 1; i < inProgressStroke.size(); i++) {
        Point p1 = inProgressStroke.get(i - 1);
        Point p2 = inProgressStroke.get(i);
        g2d.drawLine(
                x + (int) Math.round(p1.x * zoom), y + (int) Math.round(p1.y * zoom),
                x + (int) Math.round(p2.x * zoom), y + (int) Math.round(p2.y * zoom)
        );
      }
    }

    // texts (guarded: never let paint crash the map)
    try {
      for (TextItem t : textToDraw) {
        if (t == null || t.text == null) continue;
        g2d.setColor(new Color(t.rgb, true));
        int fs = Math.max(6, (int) Math.round(t.fontSize * zoom));
        g2d.setFont(new Font(safeFont(t.fontName), Font.PLAIN, fs));
        g2d.drawString(
                t.text,
                x + (int) Math.round(t.x * zoom),
                y + (int) Math.round(t.y * zoom)
        );
      }
    }
    catch (Throwable ignore) { }

    // eraser circle overlay (while erasing)
    // eraser circle overlay (while erasing OR while just previewing gum)
    Point p = (erasingNow && lastEraserLocal != null) ? lastEraserLocal : gumCursorLocal;          // if you only use this
    if (showGumCursor && p != null) {
      int r = eraserRadius;
      int cx = x + (int) Math.round(p.x * zoom);
      int cy = y + (int) Math.round(p.y * zoom);
      int rr = (int) Math.round(r * zoom);

      g2d.setStroke(new BasicStroke(1.0f));
      g2d.setColor(new Color(0, 0, 0, 80));
      g2d.drawOval(cx - rr, cy - rr, 2 * rr, 2 * rr);

      g2d.setColor(new Color(255, 255, 255, 60));
      g2d.drawOval(cx - rr - 1, cy - rr - 1, 2 * rr + 2, 2 * rr + 2);
    }


    g2d.dispose();
  }

  // ------------------- Trait description/help/editor -------------------
  @Override
  public String getDescription() {
    return "Drawing Layer (draw+text)";
  }

  @Override
  public HelpFile getHelpFile() { return null; }

  @Override
  public PieceEditor getEditor() {
    return new Ed(this);
  }

  // ------------------- PieceEditor (config UI) -------------------
  public static class Ed implements PieceEditor {

    private final DrawingLayer trait;
    private final JPanel panel = new JPanel(new GridLayout(0, 1));

    private final JTextField drawR = new JTextField(3);
    private final JTextField drawG = new JTextField(3);
    private final JTextField drawB = new JTextField(3);
    private final JTextField drawWidth = new JTextField(6);

    private final JTextField eraserRadius = new JTextField(6);

    private final JTextField fontName = new JTextField(12);
    private final JTextField fontSize = new JTextField(6);
    private final JTextField textR = new JTextField(3);
    private final JTextField textG = new JTextField(3);
    private final JTextField textB = new JTextField(3);

    public Ed(DrawingLayer trait) {
      this.trait = trait;

      drawR.setText(Integer.toString(trait.drawR));
      drawG.setText(Integer.toString(trait.drawG));
      drawB.setText(Integer.toString(trait.drawB));
      drawWidth.setText(Double.toString(trait.drawWidth));

      eraserRadius.setText(Integer.toString(trait.eraserRadius));

      fontName.setText(trait.fontName);
      fontSize.setText(Integer.toString(trait.fontSize));
      textR.setText(Integer.toString(trait.textR));
      textG.setText(Integer.toString(trait.textG));
      textB.setText(Integer.toString(trait.textB));

      panel.add(group("Draw color (R G B 0-255)", row(drawR, drawG, drawB)));
      panel.add(group("Line thickness (pixels)", drawWidth));
      panel.add(group("Gum radius (pixels)", eraserRadius));
      panel.add(group("Text font name (e.g. SansSerif)", fontName));
      panel.add(group("Text size (pixels)", fontSize));
      panel.add(group("Text color (R G B 0-255)", row(textR, textG, textB)));
    }

    @Override
    public Component getControls() { return panel; }

    @Override
    public String getType() {
      int dr = clamp(parseInt(drawR.getText(), trait.drawR), 0, 255);
      int dg = clamp(parseInt(drawG.getText(), trait.drawG), 0, 255);
      int db = clamp(parseInt(drawB.getText(), trait.drawB), 0, 255);
      double dw = clamp(parseDouble(drawWidth.getText(), trait.drawWidth), 0.5, 50.0);

      int er = clamp(parseInt(eraserRadius.getText(), trait.eraserRadius), 1, 500);

      String fn = safeFont(fontName.getText().trim().isEmpty() ? trait.fontName : fontName.getText().trim());
      int fs = clamp(parseInt(fontSize.getText(), trait.fontSize), 6, 200);
      int tr = clamp(parseInt(textR.getText(), trait.textR), 0, 255);
      int tg = clamp(parseInt(textG.getText(), trait.textG), 0, 255);
      int tb = clamp(parseInt(textB.getText(), trait.textB), 0, 255);

      SequenceEncoder se = new SequenceEncoder(',');
      se.append(dr); se.append(dg); se.append(db); se.append(dw);
      se.append(er);
      se.append(fn); se.append(fs); se.append(tr); se.append(tg); se.append(tb);

      return ID + se.getValue();
    }

    @Override
    public String getState() {
      return "";
    }

    // Optional: you can omit this; it exists as a default method on PieceEditor.
    @Override
    public void initCustomControls(JDialog dialog) { }

    private JPanel row(JComponent... comps) {
      JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
      for (JComponent c : comps) p.add(c);
      return p;
    }

    private JPanel group(String title, JComponent inner) {
      JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
      p.setBorder(new TitledBorder(title));
      p.add(inner);
      return p;
    }
  }

  // ------------------- Small helpers -------------------
  private static int parseInt(String s, int def) {
    try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
  }

  private static double parseDouble(String s, double def) {
    try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static String safeFont(String s) {
    if (s == null || s.trim().isEmpty()) return "SansSerif";
    return s.trim();
  }

  private static String b64(String s) {
    if (s == null) return "";
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String unb64(String s) {
    try {
      if (s == null || s.isEmpty()) return "";
      return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
    catch (Exception e) {
      return "";
    }
  }

  @Override
  public String getName() {
    return piece.getName();
  }

  @Override
  public Rectangle boundingBox() {
    return piece.boundingBox();
  }

  @Override
  public Shape getShape() {
    return piece.getShape();
  }
}
