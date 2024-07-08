// Choose one of them
//import qupath.lib.gui.tools.MeasurementMapper
// def colorMapper = MeasurementMapper.loadDefaultColorMaps().find {it.getName() == 'Viridis'}

import qupath.lib.color.ColorMaps
def colorMapper = ColorMaps.getColorMaps().find {it.getKey() == "Viridis"}
colorMapper = colorMapper.getValue()

//Loop starting from one annotation object with no other objects present to contstraing it
//Create bands
import qupath.lib.roi.*
import qupath.lib.objects.*
import qupath.lib.scripting.QP
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

bands = 6
radiusMicrons = 27.0

def hierarchy = QP.getCurrentHierarchy()
hierarchy.getAnnotationObjects().findAll{it.getPathClass().toString() == "CD31"}.each { first ->

    parent = first.getParent()
    parentName = parent.getName()+"-"

    first.setName(parentName+"0")
    firstROI = first.getROI()
    def plane = firstROI.getImagePlane()
    firstClass = first.getPathClass()

    //Here we do some processing to simplify the outlines and remove small holes
    geometry = firstROI.getGeometry()
    geometry = TopologyPreservingSimplifier.simplify(geometry, 5.0);
    geometry = GeometryTools.refineAreas(geometry, 200, 200)

    firstROI = GeometryTools.geometryToROI(geometry, plane)
    first.setROI(firstROI)
    
    //Expand the annotation iteratively
    for (i=1; i<=bands; i++){
        j=i-1
        currentObjects = getAnnotationObjects().findAll{it.getName() == parentName+j.toString()}
        currentObjects[0].setName(parentName+i.toString())        
        selectObjects{it.getName() == parentName+i.toString()}
        runPlugin('qupath.lib.plugins.objects.DilateAnnotationPlugin', '{"radiusMicrons": '+radiusMicrons+',  "removeInterior": false,  "constrainToParent": true}');
        currentObjects[0].setName(parentName+j.toString())
    }
    
    //Add an external band (its index is band+1)
    ringHole = getAnnotationObjects().findAll{it.getName() == parentName+bands.toString()}
    ringROI = RoiTools.combineROIs(parent.getROI(), ringHole[0].getROI(), RoiTools.CombineOp.SUBTRACT)
    ring = new PathAnnotationObject(ringROI, firstClass)
    ring.setName(parentName+(bands+1).toString())
    col = colorMapper.getColor( 0,  0,  bands);
    ring.setColorRGB(col)
    addObjects(ring)
    
    //Sort out the other rings
    for (i=bands; i>0; i--){
        j=i-1    
        toRingify = getAnnotationObjects().findAll{it.getName() == parentName+i.toString()}
        ringHole = getAnnotationObjects().findAll{it.getName() == parentName+j.toString()}
        println toRingify[0].getName() + " " +ringHole[0].getName()
        
        ringROI = RoiTools.combineROIs(toRingify[0].getROI(), ringHole[0].getROI(), RoiTools.CombineOp.SUBTRACT)
        ring = new PathAnnotationObject(ringROI, firstClass)
        ring.setName(parentName+i.toString())
        
        //Add a colour
        col = colorMapper.getColor( bands-1-j,  0,  bands);
        ring.setColorRGB(col)
        
        addObjects(ring)

        removeObjects(toRingify,true)
    }
}
resetSelection()
//resolveHierarchy()
//fireHierarchyUpdate()
