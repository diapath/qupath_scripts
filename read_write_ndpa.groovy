// Read and write NDPA files
//
// Script author: Egor Zindy
// Discussion: https://forum.image.sc/t/exporting-ndpi-ndpa-annotation-files-from-qupath-code-attached/55418
// Script was modified so that it can be sideloaded in other groovy scripts

import groovy.xml.MarkupBuilder
import org.openslide.OpenSlide
import qupath.lib.scripting.QP
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.tools.ColorToolsFX
import qupath.lib.geom.Point2
import qupath.lib.images.servers.ImageServer
import qupath.lib.common.GeneralTools
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.roi.*
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

def getImageData() {
    def imageData = QP.getCurrentImageData()

    if (imageData == null) {
        def gui = QuPathGUI.getInstance()

        if (gui != null) {
            def viewer = gui.getViewer()
            imageData = viewer.getImageData()
        }
    }
    return imageData
}

def get_osr(server) {
    // If OpenSlide metadata isn't available, load it up!
    if (server.hasProperty('osr') && server.osr){
        // Image was opened with OpenSlide
        osr = server.osr
    } else {
        // Code borrowed from qupath/qupath-extension-openslide/src/main/java/qupath/lib/images/servers/openslide/OpenslideImageServer.java 
        // Ensure the garbage collector has run - otherwise any previous attempts to load the required native library
        // from different classloader are likely to cause an error (although upon first further investigation it seems this doesn't really solve the problem...)
        System.gc();
        def uri = GeneralTools.toPath(server.getURIs()[0]).toString();
        file = new File(uri);
        osr = new OpenSlide(file);
    }
    return osr
}

// Convert a point from NDPA to QuPath coordinates
def convertPoint(point, pixelWidthNm=1, pixelHeightNm=1, OffSet_From_Top_Left_X=0, OffSet_From_Top_Left_Y=0) {
    point.x = (point.x.toDouble() / pixelWidthNm) + OffSet_From_Top_Left_X 
    point.y = (point.y.toDouble() / pixelHeightNm) + OffSet_From_Top_Left_Y 
}


// Read an NDPA file TODO check that the code still works
def read_ndpa(className="Region")
{
    def imageData = getImageData()
    if (imageData == null)
        return

    def server = imageData.getServer()
    if (server == null)
        return

    // We need the pixel size
    def cal = server.getPixelCalibration()
    if (!cal.hasPixelSizeMicrons()) {
        Dialogs.showMessageDialog("Metadata check", "No pixel information for this image!");
        return
    }

    def pixelWidthNm = cal.getPixelWidthMicrons() * 1000
    def pixelHeightNm = cal.getPixelHeightMicrons() * 1000
    
    //Aperio Image Scope displays images in a different orientation
    //TODO Is this in the metadatata? Is this likely to be a problem?
    //print(server.dumpMetadata())
    def rotated = false

    def ImageCenter_X = server.getWidth()/2
    def ImageCenter_Y = server.getHeight()/2

    def osr = get_osr(server)

    // need to add annotations to hierarchy so qupath sees them
    def hierarchy = imageData.getHierarchy()
        
    //*********Get NDPA automatically based on naming scheme 
    def path = GeneralTools.toPath(server.getURIs()[0]).toString()+".ndpa";

    def NDPAfile = new File(path)
    if (!NDPAfile.exists()) {
        println "No NDPA file for this image..."
        return
    }

    //Get X Reference from OPENSLIDE data
    //The Open slide numbers are actually offset from IMAGE center (not physical slide center). 
    //This is annoying, but you can calculate the value you need -- Offset from top left in Nanometers. 
    OffSet_From_Top_Left_X = ImageCenter_X
    OffSet_From_Top_Left_Y = ImageCenter_Y

    osr.getProperties().each { k, v ->
        if(k.equals("hamamatsu.XOffsetFromSlideCentre")){
            OffSet_From_Top_Left_X -= v.toDouble()/pixelWidthNm
        }
        if(k.equals("hamamatsu.YOffsetFromSlideCentre")){
            OffSet_From_Top_Left_Y -= v.toDouble()/pixelHeightNm
        }
    }

    //Read files
    def text = NDPAfile.getText()
    def list = new XmlSlurper().parseText(text)

    list.ndpviewstate.each { ndpviewstate ->
        def annotationName = ndpviewstate.title.toString().trim()
        def annotationClassName = className
        def annotationType = ndpviewstate.annotation.@type.toString().toUpperCase()
        def annotationColor = ndpviewstate.annotation.@color.toString().toUpperCase()

        def details = ndpviewstate.details.toString()
        //println annotationName+" ("+annotationType+") ("+annotationClassName+") "+details
    
        roi = null
        
        if (annotationType == "CIRCLE") {
            //special case
            def point = new Point2(ndpviewstate.annotation.x, ndpviewstate.annotation.y)
            convertPoint(point, pixelWidthNm, pixelHeightNm, OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y)

            def rx = ndpviewstate.annotation.radius.toDouble() / pixelWidthNm
            def ry = ndpviewstate.annotation.radius.toDouble() / pixelHeightNm
            roi = new EllipseROI(point.x-rx,point.y-ry,rx*2,ry*2,null);
        }
        
        if (annotationType == "LINEARMEASURE") {
            //special case
            def pt1 = new Point2(ndpviewstate.annotation.x1, ndpviewstate.annotation.y1)
            convertPoint(pt1, pixelWidthNm, pixelHeightNm, OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y)
            def pt2 = new Point2(ndpviewstate.annotation.x2, ndpviewstate.annotation.y2)
            convertPoint(pt2, pixelWidthNm, pixelHeightNm, OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y)
            roi = new LineROI(pt1.x,pt1.y,pt2.x,pt2.y);
        }

        if (annotationType == "PIN") {
            def point = new Point2(ndpviewstate.annotation.x, ndpviewstate.annotation.y)
            convertPoint(point, pixelWidthNm, pixelHeightNm, OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y)
            roi = new PointsROI(point.x,point.y);
        }
            
        // All that's left if FREEHAND which handles polygons, polylines, rectangles
        ndpviewstate.annotation.pointlist.each { pointlist ->
            def tmp_points_list = []
            pointlist.point.each{ point ->
                if (rotated) {
                    X = point.x.toDouble()
                    Y = h - point.y.toDouble()
                }
                else {
                    X = point.x.toDouble()
                    Y =  point.y.toDouble()
                }

                tmp_points_list.add(new Point2(X, Y))
            } 
    
            //Adjust each point relative to SLIDECENTER coordinates and adjust for pixelsPerMicron
            for ( point in tmp_points_list){
                convertPoint(point, pixelWidthNm, pixelHeightNm, OffSet_From_Top_Left_X, OffSet_From_Top_Left_Y)
            }
            
            if (annotationType == "FREEHAND") {
                isClosed = 1 //ndpviewstate.annotation.closed.toBoolean() //XXX PLEASE PLEASE PLEASE USE THE POLYGON TOOL!!!!!
                isRectangle = (ndpviewstate.annotation.specialtype.toString() == "rectangle")
                if (isRectangle) {
                    x1 = tmp_points_list[0].x
                    y1 = tmp_points_list[0].y
                    x3 = tmp_points_list[2].x
                    y3 = tmp_points_list[2].y
                    roi = new RectangleROI(x1,y1,x3-x1,y3-y1);
                }
                else if (isClosed)
                    roi = new PolygonROI(tmp_points_list);
                else
                    roi = new PolylineROI(tmp_points_list, null);
            }
            
        }
        
        if (roi != null)
        {
            def annotation = new PathAnnotationObject(roi)
            annotation.setName(annotationName)        
            if (annotationClassName)
            {
                //TODO (validate) and add the new class if it doesn't already exist:
                //if (!PathClassFactory.classExists(annotationClassName))
                annotation.setPathClass(PathClassFactory.getPathClass(annotationClassName))
            }
            
            if (details) {          
                annotation.setDescription(details)
            }

            annotation.setLocked(true)
            hierarchy.addPathObject(annotation) //, false)
        }

    }

}

//Here we extract the polygon coords
def getPointList(ring) {
    def pointlist = []
    coords = ring.getCoordinates()
    coords[0..<coords.length-1].each { coord ->
        pointlist.add([coord.x, coord.y])
    }
    //println "---"
    return pointlist
}

def write_ndpa() {
    def imageData = getImageData()
    if (imageData == null)
        return

    def server = imageData.getServer()
    if (server == null)
        return

    // We need the pixel size
    def cal = server.getPixelCalibration()
    if (!cal.hasPixelSizeMicrons()) {
        Dialogs.showMessageDialog("Metadata check", "No pixel information for this image!");
        return
    }

    def pixelWidthNm = cal.getPixelWidthMicrons() * 1000
    def pixelHeightNm = cal.getPixelHeightMicrons() * 1000
    
    //Aperio Image Scope displays images in a different orientation
    //TODO Is this in the metadatata? Is this likely to be a problem?
    //print(server.dumpMetadata())
    def rotated = false

    def ImageCenter_X = server.getWidth()/2
    def ImageCenter_Y = server.getHeight()/2

    def osr = get_osr(server)

    //*********Get NDPA automatically based on naming scheme 
    def path = GeneralTools.toPath(server.getURIs()[0]).toString()+".ndpa";

    def NDPAfile = new File(path)

    //Get X Reference from OPENSLIDE data
    //The Open slide numbers are actually offset from IMAGE center (not physical slide center). 
    //This is annoying, but you can calculate the value you need -- Offset from top left in Nanometers. 
    OffSet_From_Top_Left_X = ImageCenter_X
    OffSet_From_Top_Left_Y = ImageCenter_Y

    osr.getProperties().each { k, v ->
        if(k.equals("hamamatsu.XOffsetFromSlideCentre")){
            OffSet_From_Top_Left_X -= v.toDouble()/pixelWidthNm
        }
        if(k.equals("hamamatsu.YOffsetFromSlideCentre")){
            OffSet_From_Top_Left_Y -= v.toDouble()/pixelHeightNm
        }
    }

    // need to add annotations to hierarchy so qupath sees them
    def hierarchy = imageData.getHierarchy()
    def pathObjects = hierarchy.getAnnotationObjects()

    //create a list of annotations
    def list_annot = []
    def ndpIndex = 0

    pathObjects.each { pathObject ->
        //We make a list of polygons, each has an exterior and interior rings
        geometry = pathObject.getROI().getGeometry()

        //Here we do some processing to simplify the outlines and remove small holes
        geometry = TopologyPreservingSimplifier.simplify(geometry, 5.0);
        geometry = GeometryTools.refineAreas(geometry, 200, 200)
        var polygons = PolygonExtracter.getPolygons(geometry);

        polygons.each { polygon ->

            //here we create a list of rings, we'll need to treat the first one differently
            def rings = [ polygon.getExteriorRing() ]
            def nRings = polygon.getNumInteriorRing();
            for (int i = 0; i < nRings; i++) {
                var ring = polygon.getInteriorRingN(i);
                rings.add(ring)
            }

            rings.eachWithIndex { ring, index ->
                def annot = [:]
                if (index == 0) {
                    annot['title'] = pathObject.getName()
                    annot['details'] = pathObject.getPathClass()
                    annot['color'] = '#' + Integer.toHexString(ColorToolsFX.getDisplayedColorARGB(pathObject)).substring(2)
                    isFirst = false
                } else {
                    annot['title'] = "clear"
                    annot['details'] = "clear"
                    annot['color'] = '#000000'
                }

                annot['id'] = ++ndpIndex
                annot['coordformat'] = 'nanometers'
                annot['lens'] = 0.445623
                annot['x'] = ImageCenter_X.toInteger()
                annot['y'] = ImageCenter_Y.toInteger()
                annot['z'] = 0
                annot['showtitle'] = 0
                annot['showhistogram'] = 0
                annot['showlineprofile'] = 0
                annot['type'] = 'freehand'
                annot['displayname'] = 'AnnotateFreehand'
                annot['measuretype'] = 0
                annot['closed'] = 1

                //add the point list
                annot['pointlist'] = getPointList(ring)
                list_annot.add(annot)
            }
        }
    }

    //make an XML string
    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    xml.mkp.xmlDeclaration(version: "1.0", encoding: "utf-8", standalone: "yes")
    xml.annotations {
        list_annot.each { annot ->
            ndpviewstate('id':annot['id']) {
                title(annot['title'])
                details(annot['details'])
                coordformat(annot['coordformat'])
                lens(annot['lens'])
                x(annot['x'])
                y(annot['y'])
                z(annot['z'])
                showtitle(annot['showtitle'])
                showhistogram(annot['showhistogram'])
                showlineprofile(annot['showlineprofile'])

                //Annotation object
                annotation(type:annot['type'], displayname:annot['displayname'], color:annot['color']) {
                    measuretype(annot['measuretype'])
                    closed(annot['closed'])
                    pointlist {
                        annot['pointlist'].each { pt ->
                            point {
                                x( ((pt[0] - OffSet_From_Top_Left_X ) * pixelWidthNm).toInteger() )
                                y( ((pt[1] - OffSet_From_Top_Left_Y ) * pixelHeightNm).toInteger() )
                            }
                        }
                    }
                }
            }
        }
    }

    NDPAfile.write(writer.toString())
}

//read_ndpa()
//write_ndpa()
