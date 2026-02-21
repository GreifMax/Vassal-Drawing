# Vassal-Drawing
Video demonstration: https://youtu.be/WIi4VKT9eQQ

As I began designing modules, I quickly ran into trouble trying to make some sheets customizable, so I decided to try creating an external class to enable drawing. In order to do that I’ve created a transparent piece as large as the map, then used scripting to treat it as a screen, so the only hard part was creating a simple paint software for that (thanks GPT5.2, I don’t know Java).

## Setup guide and tutorial
Right now, if you want to try it out on one of your modules:
 - Take the drawing folder and put it in the main directory of the vmod file.
 - Modify the buildFile.xml (in the vmod file) on one of the first lines (“<VASSAL.build.module.BasicCommandEncoder/>”) so that it becomes “<drawing.DrawingCommandEncoder/>”
 - Create a transparent piece as large as the map and put it in an At-Start-Stack in the middle of the map.
 - Add the Does Not Stack trait with Move=NEVER.
 - Import the “drawing.DrawingLayer” class.
 - Put it as a trait. You can set up Draw/Text color RGB, line thickness, gum radius, text font and text size.

This will give you the minimum setup, that is, CTRL+D to enter Draw mode, CTRL+T to enter Text mode, ESC to leave any mode. Use LMB to draw/create a text box/edit a created text box. Hold down CTRL and use LMB to erase text/drawing. Note: I highly suggest to use only the draw’s eraser function, as the text’s eraser is laggy and doesn’t work on drawing, while the draw’s eraser works on both.

In order to add buttons I’ve used the GumLatch Dynamic Property, which speaks directly with the Java class, to toggle a virtual CTRL on/off, while the rest is just some work with Global Key Commands and Global Properties.

If you want to try my implementation, download the "Raider!" module from Vassal (https://vassalengine.org/library/projects/raider_sovietviper) and open a vessels' tab.

## "Roadmap"
Following the suggestions of cholmcc (https://forum.vassalengine.org/u/cholmcc/summary) I'll try to create a sub-element for a map instead of a trait for a piece.
