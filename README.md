# Vassal-Drawing
Video demonstration: https://youtu.be/c_-kEHwLE4I

As I began designing modules, I quickly ran into trouble trying to make some sheets customizable, so I decided to try creating an external class to enable drawing. In 1.0, I used a transparent piece on which an outside script could draw thanks to a simple drawing program (written by GPT5.2, I don't know Java). For 2.0, following the advice of cholmcc, I used a map overlay (written instead by Gemini3.1) and I added complete SVG usage.

## Installation instructions:
 - Open your .vmod with a zip editor and drop the 'drawing' folder inside.
 - When in the VASSAL Editor, right-click your Map, select 'Add Imported Class', and type 'drawing.MapAnnotator'.

After you've installed the script, you can edit RGB for text and drawings, change buttons' names and create custom shortcuts for the different functions.

## Usage instructions:
 - LMB on any button to select that function.
 - Hold LMB to draw or use the gum (when selected).
 - LMB once to write text (when selected).
 - RMB once on Shapes to open the shape selector (LMB on the desired shape to mark as favourite).
 - Hold LMB to start creating a shape, release LMB to create it.
 - LMB once on created text to edit.

Enjoy!

## Notes for editors:
The software is not optimized in any way, so the gum tends to lag quite a lot. I'm pretty sure there's a way to make it flawless, while still keeping all deleting functionalities. Custom button icons are also missing. 
For the future it would be great to make text interact with the Vassal module (for example, showing global variables), and to make users add custom shapes easily.

Thanks again to cholmcc (https://forum.vassalengine.org/u/cholmcc/summary) for giving suggestions on how to improve the original 1.0 script.
