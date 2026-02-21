package drawing;

import VASSAL.build.module.BasicCommandEncoder;
import VASSAL.counters.Decorator;
import VASSAL.counters.GamePiece;

public class DrawingCommandEncoder extends BasicCommandEncoder {

  @Override
  public Decorator createDecorator(String type, GamePiece inner) {
    if (type != null && type.startsWith(DrawingLayer.ID)) {
      return new DrawingLayer(type, inner);
    }
    return super.createDecorator(type, inner);
  }
}
