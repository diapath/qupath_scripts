///////////////////////////////////////////////////////////////////////////////////
// Set These:
///////////////////////////////////////////////////////////////////////////////////

// The downsample is a function of the width of the PNG images embedded inside the SVG.
def bitmapWidth = 2000

// The preferred length of the scale bar as a proportion of the figure width
def preferredLengthProportion = 0.25

// The color used for text and scale bar
def theColor = Color.BLACK

// The preferred font size as a proportion of the figure height:
def preferredFontSizeProportion = 0.04

// The preferred line thickness as a proportion of the figure height:
def preferredLineThicknessProportion = 0.01

// To add a colorscale, use a saved density map (.json)
densityJson = "density_map.json"

///////////////////////////////////////////////////////////////////////////////////

def imageData = getCurrentImageData()
def server = imageData.getServer()

def name = GeneralTools.stripExtension(getProjectEntry().getImageName())
def path = GeneralTools.toPath(server.getURIs()[0]).toString()+".svg"

def viewer = getCurrentViewer()
def options = viewer.getOverlayOptions()


// Retrieve the selected annotation if there is one. Then use its bounds to define the image considered
def selectedObjects = getSelectedObjects()

if (selectedObjects.isEmpty()) {
    // Get the dimensions of the whole image
    def width = server.getWidth()
    def height = server.getHeight()

    // Create a RegionRequest for the entire image at full resolution (downsample = 1.0)
    request = RegionRequest.createInstance(server.getPath(), 1.0, 0, 0, width, height)
    downsample = (int)(width / bitmapWidth)    
} else {
    def selectedAnnotation = selectedObjects[0]

    def roi = selectedAnnotation.getROI()
    request = RegionRequest.createInstance(server.getPath(), 1.0, (int)roi.getBoundsX(), (int)roi.getBoundsY(), (int)roi.getBoundsWidth(), (int)roi.getBoundsHeight())
    downsample = (int)(roi.getBoundsWidth() / bitmapWidth)
}

if (downsample < 1) downsample = 1
println("Downsample for a requested width of $bitmapWidth pixels: $downsample")

def builder = new SvgBuilder(viewer)
    .imageData(imageData)
    .options(options)
    .downsample(downsample) //Calculated automatically depending on the requested bitmap width
    .region(request)

// Function to draw a gradient-filled rectangle using LinearGradientPaint
// Currently Viridis, Svidro2, Plasma, Magma, Inferno, Jet
void drawGradientRectangle(Graphics2D g2d, String lutName, int x, int y, int width, int height) {
    
    //Get the colormap based on lutName
    def colormaps = ColorMaps.getColorMaps()
    def theMap = colormaps.get(lutName)
    def lutLength = theMap.r.length // Length may not be 255, Jet for example!
    
    // Prepare the color array and fraction array for the gradient
    Color[] colors = new Color[lutLength];
    float[] fractions = new float[lutLength];
    
    for (int i = 0; i < lutLength; i++) {
        r = (byte) ColorTools.do8BitRangeCheck(theMap.r[i])
        g = (byte) ColorTools.do8BitRangeCheck(theMap.g[i])
        b = (byte) ColorTools.do8BitRangeCheck(theMap.b[i])
        
        colors[i] = new Color(r & 255, g & 255, b & 255);
        fractions[i] = i / (float)lutLength;
    }

    // Create a LinearGradientPaint object
    LinearGradientPaint linearGradient = new LinearGradientPaint(
        new Point2D.Float(x, y + height),
        new Point2D.Float(x, y),
        fractions,
        colors
    );

    // Set the paint of the Graphics2D object to the gradient
    g2d.setPaint(linearGradient);

    // Fill the rectangle with the gradient
    g2d.fillRect(x, y, width, height);
}

void drawColorbar(Graphics2D g2d, String jsonFilename, int x, int y, int w, int h, Font theFont, Color theColor) {
    DecimalFormat df = new DecimalFormat("#");
    
    String pixelClassifierFolder = buildFilePath(PROJECT_BASE_DIR, "classifiers", "density_maps")
    Path jsonPath=Path.of(pixelClassifierFolder,jsonFilename)

    mapBuilder = QP.loadDensityMap((String)jsonPath)

    def minDisplay = mapBuilder.colorModelBuilder.band.minDisplay
    def maxDisplay = mapBuilder.colorModelBuilder.band.maxDisplay
    def colorMapName = mapBuilder.colorModelBuilder.band.colorMapName

    // Draw the gradient rectangle
    drawGradientRectangle(g2d, colorMapName, x, y, w, h);

    g2d.setFont(theFont)

    // Get the FontMetrics
    def metrics = g2d.getFontMetrics(theFont)
    
    labelText = df.format(minDisplay)
    g2d.setColor(theColor)
    g2d.drawString(labelText, (int) (x+w*1.02), (int) (y+h))
    
    labelText = df.format(maxDisplay)
    g2d.setColor(theColor)
    g2d.drawString(labelText, (int) (x+w*1.02), (int) (y+metrics.getAscent()))
}

def getPermittedScale(double preferredLength) {
    def permittedScales = new double[]{0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 250, 400, 500, 800, 1000, 2000, 5000, 10000, 20000, 50000, 100000};    
    double minDiff = Double.POSITIVE_INFINITY;
    double scaleValue = Double.NaN;
    for (double d : permittedScales) {
        double tempDiff = Math.abs(d - preferredLength);
        if (tempDiff < minDiff) {
            scaleValue = d;
            minDiff = tempDiff;
        }
    }
    return scaleValue;
}

def drawScalebar(Graphics2D g2d, PixelCalibration cal, int startX, int startY, double preferredLength, float lineWidth, Font theFont, Color theColor) {
    DecimalFormat df = new DecimalFormat("#.##");    
    double pxSize = 1.0;
    String unit = "px";

    if (cal.hasPixelSizeMicrons()) {
        pxSize = cal.getPixelWidthMicrons();
        unit = GeneralTools.micrometerSymbol();
    }

    // Find the permitted scalebar size closest to the preferred length (an 8th of the region width)
    scaledLength = getPermittedScale(pxSize * preferredLength);
    scaledLengthPixels = scaledLength / pxSize;

    // Switch to mm if appropriate
    String labelText = df.format(scaledLength) + " " + unit;
    if (scaledLength >= 1000 && GeneralTools.micrometerSymbol().equals(unit)) {
        labelText = df.format(scaledLength / 1000) + " mm";
    }
			
    g2d.setColor(theColor)
    
    // Set the stroke with the desired width (in pixels)
    g2d.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

    def endX = startX + scaledLengthPixels
    def endY = startY    
    g2d.drawLine((int) startX, (int) startY, (int) endX, (int) endY)


    g2d.setFont(theFont)

    // Get the FontMetrics
    def metrics = g2d.getFontMetrics(theFont)
    
    // Calculate the y position to center the text vertically
    def textHeight = metrics.getHeight()
    def textWidth = (int)g2d.getFontMetrics().getStringBounds(labelText, g2d).getWidth();
    
    // Draw the label text
    g2d.drawString(labelText, (int) (startX + scaledLengthPixels/2 - textWidth / 2), (int) (startY - textHeight/4))
}

///////////////////////////////////////////////////////////////////////////////////
    
g2d = builder.buildGraphics(name + "-image.png") 

region = builder.region
originX = region.getX()
originY = region.getY()
width = region.getWidth()
height = region.getHeight()

///////////////////////////////////////////////////////////////////////////////////

// The line position and width in pixels
def startX = originX+width*0.05
def startY = originY+height*0.95

//The pixel calibration
PixelCalibration cal = server.getPixelCalibration();

def lineWidth = (float)(height * preferredLineThicknessProportion) // Set the line width in pixels

// Set the font with specific size and weight
def fontSize = (int)(height * preferredFontSizeProportion)
def fontWeight = Font.PLAIN // Specify the font weight
def theFont = new Font("Arial", fontWeight, fontSize)

def preferredLength = (double)(width * preferredLengthProportion)

drawScalebar(g2d, cal, (int)startX, (int)startY, preferredLength, lineWidth, theFont, theColor)

///////////////////////////////////////////////////////////////////////////////////

// Set the dimensions and position for the gradient rectangle (for the densitymap min/max)
int x = originX + width * 0.85
int y = originY + height * 0.05
int w = width*0.05; // Width of the rectangle
int h = height*0.9; // Height of the rectangle

if (densityJson != "")
    drawColorbar(g2d, densityJson, x, y, w, h, theFont, theColor)

//Generate the SVG file and save it
doc = g2d.getSVGDocument()
new File(path).text = doc

println("All done!")

import qupath.lib.scripting.QP
import java.nio.file.Path

import qupath.lib.extension.svg.SvgTools.SvgBuilder
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.common.GeneralTools;
import qupath.lib.common.ColorTools
import qupath.lib.gui.viewer.QuPathViewer
import qupath.lib.color.ColorMaps
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.RectangleROI

import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.geom.Point2D
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font

import java.text.DecimalFormat;

// Code lifted from
//   https://www.imagescientist.com/scripting-export-images
//   https://forum.image.sc/t/exporting-rendered-svg-images-in-batch-mode/52045
//   https://github.com/qupath/qupath/blob/main/qupath-gui-fx/src/main/java/qupath/lib/gui/viewer/Scalebar.java
//   https://github.com/qupath/qupath/blob/main/qupath-extension-svg/src/main/java/qupath/lib/extension/svg/SvgTools.java
