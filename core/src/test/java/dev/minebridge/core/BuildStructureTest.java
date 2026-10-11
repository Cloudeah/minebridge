package dev.minebridge.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildStructureTest {
    private BuildPlanner.Plan plan(int rotation,JsonObject... blocks){return new BuildPlanner().plan(object("origin",object("x",10,"y",64,"z",20),"rotation",rotation,"blocks",List.of(blocks)),type->switch(type){case "minecraft:oak_door"->BuildPlanner.Kind.DOOR;case "minecraft:red_bed"->BuildPlanner.Kind.BED;default->BuildPlanner.Kind.SINGLE;});}
    private JsonObject door(int y,String half){return object("type","minecraft:oak_door","y",y,"properties",object("half",half));}
    private JsonObject bed(int x,int z,String part,String face){return object("type","minecraft:red_bed","x",x,"z",z,"properties",object("part",part,"facing",face));}
    private void invalid(JsonObject... blocks){assertEquals("invalid_request",assertThrows(BridgeException.class,()->plan(0,blocks)).code);}

    @Test void doorCostsOneItemAndIncludesBothCells(){var p=plan(0,door(0,"lower"),door(1,"upper"));assertEquals(Map.of("minecraft:oak_door",1),p.materials());assertEquals(1,p.placements().size());assertEquals(2,p.blocks().size());}
    @Test void singleDoorExpandsItsUpperCell(){var p=plan(0,door(0,"lower"));assertEquals(65,p.blocks().getLast().y());assertEquals("upper",string(p.blocks().getLast().properties(),"half"));}
    @Test void upperOnlyDoorFindsItsPrimaryBeforeExecution(){var p=plan(0,door(1,"upper"));assertEquals(64,p.blocks().getFirst().y());assertEquals("lower",string(p.blocks().getFirst().properties(),"half"));}
    @Test void reversedBedIsOrderedFootFirst(){var p=plan(0,bed(1,0,"head","east"),bed(0,0,"foot","east"));assertEquals(Map.of("minecraft:red_bed",1),p.materials());assertEquals("foot",string(p.blocks().getFirst().properties(),"part"));assertEquals(11,p.blocks().getLast().x());}
    @Test void headOnlyBedFindsItsFoot(){var p=plan(0,bed(1,0,"head","east"));assertEquals(10,p.blocks().getFirst().x());assertEquals(2,p.blocks().size());}
    @Test void rotationsKeepBedGeometryAndFacingTogether(){for(int rotation:new int[]{0,90,180,270}){var p=plan(rotation,bed(0,0,"foot","east"),bed(1,0,"head","east"));var foot=p.blocks().getFirst();var head=p.blocks().getLast();assertEquals(1,p.placements().size());assertEquals(List.of("east","south","west","north").get(rotation/90),string(foot.properties(),"facing"));assertEquals(1,Math.abs(foot.x()-head.x())+Math.abs(foot.z()-head.z()));}}
    @Test void sharedPropertiesPropagateToTheOtherDoorHalf(){JsonObject upper=door(1,"upper");child(upper,"properties").addProperty("facing","east");child(upper,"properties").addProperty("hinge","right");var p=plan(0,door(0,"lower"),upper);assertEquals("east",string(p.blocks().getFirst().properties(),"facing"));assertEquals("right",string(p.blocks().getFirst().properties(),"hinge"));}
    @Test void conflictingSharedPropertiesAreRejected(){JsonObject lower=door(0,"lower"),upper=door(1,"upper");child(lower,"properties").addProperty("open","true");child(upper,"properties").addProperty("open","false");invalid(lower,upper);}
    @Test void implicitCellCannotOverwriteAnotherBlueprintBlock(){invalid(door(0,"lower"),object("type","minecraft:stone","y",1));invalid(bed(0,0,"foot","east"),object("type","minecraft:stone","x",1));}
    @Test void twoOverlappingStructuresAreRejected(){invalid(door(0,"lower"),door(1,"lower"));invalid(bed(0,0,"foot","east"),bed(1,0,"foot","east"));}
    @Test void bedFacingMustBeExplicitAndHorizontal(){invalid(object("type","minecraft:red_bed"));invalid(bed(0,0,"foot","up"));}
    @Test void invalidStructurePartsAreRejected(){invalid(door(0,"side"));invalid(bed(0,0,"pillow","east"));}
    @Test void ordinaryOrUnknownMechanicsKeepOneItemPerCell(){var p=plan(0,object("type","custom:door"),object("type","custom:door","y",1));assertEquals(Map.of("custom:door",2),p.materials());assertEquals(2,p.placements().size());}
    @Test void multipleStructuresAndOrdinaryBlocksSumMaterials(){var p=plan(0,door(0,"lower"),door(1,"upper"),object("type","minecraft:oak_door","x",2),object("type","minecraft:stone","x",4));assertEquals(Map.of("minecraft:oak_door",2,"minecraft:stone",1),p.materials());assertEquals(5,p.blocks().size());}
    @Test void propertiesRequireStrings(){invalid(object("type","minecraft:oak_door","properties",object("open",true)));invalid(object("type","minecraft:stone","properties",object("axis",List.of("x"))));}
    @Test void absoluteAndImplicitCoordinatesStayWithinBounds(){var planner=new BuildPlanner();assertEquals("invalid_request",assertThrows(BridgeException.class,()->planner.plan(object("origin",object("x",30000000),"blocks",List.of(object("type","minecraft:stone","x",1))))).code);assertEquals("invalid_request",assertThrows(BridgeException.class,()->planner.plan(object("origin",object("y",2048),"blocks",List.of(door(0,"lower"))),ignored->BuildPlanner.Kind.DOOR)).code);}
    @Test void expansionCannotExceedTheCellLimit(){List<JsonObject> blocks=new ArrayList<>();for(int i=0;i<2049;i++)blocks.add(object("type","minecraft:oak_door","x",i%64-32,"z",i/64-32));assertEquals("invalid_request",assertThrows(BridgeException.class,()->new BuildPlanner().plan(object("blocks",blocks),ignored->BuildPlanner.Kind.DOOR)).code);}
}
