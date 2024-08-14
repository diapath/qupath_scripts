import groovy.xml.XmlSlurper

// When we split the filename into chunks, we indicate if either
// first and/or last chunks are to be considered as prefix and suffix:
// 
// left_middle_right -> left, middle, right
//
// or
//
// prefix_left_middle_right_suffix -> 
// prefix_left_suffix, prefix_middle_suffix, prefix_right_suffix


// *************************************************************************
// SET THESE
// *************************************************************************

// Change this to false to rename the scene
def dryRun = true

// Need more info? enable verbose mode
def verbose = false

// The string separator we use for splitting the filename into chunks
def chunkSeparator = '_'

// Presence of a prefix and/or suffix to consider
def hasPrefix = false
def hasSuffix = true

// *************************************************************************

def getSceneOffsets(server, verbose=false) {
    def dump = server.dumpMetadata()
    def cziMetadata = new XmlSlurper().parseText(dump)
    def sceneOffsets = []
    
    // Find all Image elements that also have a StageLabel child
    def images = cziMetadata.'**'.findAll { it.name() == 'Image' && it.StageLabel.find { it.name() == 'StageLabel' } }

    // Iterate through each image
    images.each { image ->
        // Access StageLabel attributes
        def stageLabel = image.StageLabel[0]
        def name = stageLabel.@Name
        def x = stageLabel.@X
        def xUnit = stageLabel.@XUnit
        def y = stageLabel.@Y
        def yUnit = stageLabel.@YUnit
        def z = stageLabel.@Z
        def zUnit = stageLabel.@ZUnit
        
        sceneOffsets.add([x.toDouble(),y.toDouble(),z.toDouble(),xUnit,yUnit,zUnit])
        
        // Print or process the information as needed
        if (verbose) println("Image: $name, X: $x $xUnit, Y: $y $yUnit, Z: $z $zUnit")
    }
    return sceneOffsets
}

def getSortedIndex(xOffsets, sceneIndex) {
    // xOffset corresponding to the given scene position index
    def xOffset = xOffsets[sceneIndex]

    // Sort the xOffsets and find the index of the given xOffset in the sorted list
    def sortedXOffsets = xOffsets.sort()
    
    // Return the new index
    return sortedXOffsets.indexOf(xOffset)
}

// Getting the name from the metadata.
// If the name is changed, does the one in the metadata stays the same?
def server = QP.getCurrentImageData().getServer()
def entry = getProjectEntry()
def imageName = entry.getImageName() //server.getMetadata().getName()

println("Original name : $imageName")

if (!imageName.contains(".czi - Scene #")) {
    println("Can't split that, not a czi Scene name!")
    return
}

// Here we split the image name from the scene number
def splitNameScene = imageName.split(".czi - Scene #").toList()
def sceneIndex = splitNameScene[1].toInteger()-1
imageName = splitNameScene[0]

// Here we split the imageName entry into multiple chunks, each corresponding to a scene name from left to right
sceneNames = imageName.tokenize(chunkSeparator)

// Extract prefix and suffix from the list if required
def prefix = hasPrefix ? sceneNames.remove(0)+chunkSeparator : ''
def suffix = hasSuffix ? chunkSeparator+sceneNames.remove(sceneNames.size()-1) : ''

if (verbose) println("Split chunks are: $sceneNames")
if (verbose) println("Scene index is: $sceneIndex")
    
// For each scene, get its x,y,z offset position
def sceneOffsets = getSceneOffsets(server, verbose)
def xOffsets = sceneOffsets.collect { it[0] }
if (verbose) println("Scene offsets (x) are: $xOffsets")

// Here we get a chunk index corresponding to the xOffset of the scene, sorted left to right
sortedIndex = getSortedIndex(xOffsets, sceneIndex)

// Here we get the corresponding sceneNames entry
def newName = sceneNames[sortedIndex]

// Here we add the prefix and suffix using the initial chunk separator.
newName = prefix + newName + suffix

// All done, we can now rename the entry (unless this was a test run)
if (dryRun == true) {
    println("Dry run output: $newName")
} else {
    println("Renamed output: $newName")
    entry.setImageName(newName)
}
