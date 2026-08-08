# Vassal-Drawing
Video demonstration: WIP

As I began designing modules, I quickly ran into trouble trying to make some sheets customizable, so I decided to try creating an external class to enable drawing. In 1.0, I used a transparent piece on which an outside script could draw thanks to a simple drawing program (written by GPT5.2, I don't know Java). For 2.0, following the advice of cholmcc, I used a map overlay (written instead by Gemini3.1) and I added complete SVG usage.

## Installation instructions:
 - Open your .vmod with a zip editor and drop the 'drawing' folder inside.
 - When in the VASSAL Editor, right-click your Map, select 'Add Imported Class', and type 'drawing.MapAnnotator'.

After you've installed the script, you can edit all the options inside the "Drawing Annotator \[Map Annotator]"

## Usage instructions:
 - LMB on any button to select that function.
 - Hold LMB to draw or use the gum (when selected).
 - LMB once to write text (when selected).
 - RMB once on Shapes to open the shape selector (LMB on the desired shape to mark as favourite).
 - Hold LMB to start creating a shape, release LMB to create it.
 - LMB once on created text to edit.

Enjoy!

Thanks to cholmcc (https://forum.vassalengine.org/u/cholmcc/summary) for giving suggestions on how to improve the original 1.0 script.
Thanks to czarkearonetto for finding a bug in v2.1.0
