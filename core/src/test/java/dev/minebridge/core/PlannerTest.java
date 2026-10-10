package dev.minebridge.core;

import org.junit.jupiter.api.*;
import java.util.*;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {
    private CraftPlanner.Ingredient ingredient(String... choices){return new CraftPlanner.Ingredient(List.of(choices));}
    private CraftPlanner.Recipe recipe(String out,int count,CraftPlanner.Ingredient... in){return new CraftPlanner.Recipe(out,out,count,List.of(in),"inventory",Map.of());}
    @Test void pathGoesAroundObstacle(){var route=new Pathfinder().plan(c->c.y()==0 && !(c.x()==1&&c.z()==0)?Pathfinder.Terrain.WALKABLE:Pathfinder.Terrain.BLOCKED,new Pathfinder.Cell(0,0,0),new Pathfinder.Cell(2,0,0),100);assertEquals(new Pathfinder.Cell(2,0,0),route.cells().getLast());assertFalse(route.cells().contains(new Pathfinder.Cell(1,0,0)));}
    @Test void unknownIsNotWalkable(){BridgeException e=assertThrows(BridgeException.class,()->new Pathfinder().plan(c->Pathfinder.Terrain.UNKNOWN,new Pathfinder.Cell(0,0,0),new Pathfinder.Cell(2,0,0),100));assertEquals("chunk_unavailable",e.code);}
    @Test void blockedDiffersFromUnloaded(){BridgeException e=assertThrows(BridgeException.class,()->new Pathfinder().plan(c->Pathfinder.Terrain.BLOCKED,new Pathfinder.Cell(0,0,0),new Pathfinder.Cell(2,0,0),100));assertEquals("path_unreachable",e.code);}
    @Test void routeHasFiniteBudget(){assertThrows(BridgeException.class,()->new Pathfinder().plan(c->Pathfinder.Terrain.WALKABLE,new Pathfinder.Cell(0,0,0),new Pathfinder.Cell(20,0,20),5));}
    @Test void craftResolvesDependenciesAndOutputMultiplicity(){CraftPlanner p=new CraftPlanner(List.of(recipe("plank",4,ingredient("log")),recipe("stick",4,ingredient("plank"),ingredient("plank")),recipe("tool",1,ingredient("stick"),ingredient("plank"))));var plan=p.plan("tool",1,Map.of("log",1),Set.of("inventory"));assertTrue(plan.executable());assertEquals(List.of("plank","stick","tool"),plan.stages().stream().map(CraftPlanner.Stage::item).toList());assertEquals(3,plan.finalInventory().get("stick"));}
    @Test void craftChoosesAvailableIngredientAlternative(){var plan=new CraftPlanner(List.of(recipe("plank",4,ingredient("oak","birch")))).plan("plank",4,Map.of("birch",1),Set.of("inventory"));assertTrue(plan.executable());assertEquals(List.of("birch"),plan.stages().getFirst().ingredients());}
    @Test void craftChoosesRecipeWithAvailableResources(){var plan=new CraftPlanner(List.of(recipe("out",1,ingredient("missing")),recipe("out",1,ingredient("available")))).plan("out",1,Map.of("available",1),Set.of("inventory"));assertTrue(plan.executable());assertEquals(List.of("available"),plan.stages().getFirst().ingredients());}
    @Test void missingIngredientIsPrecise(){var plan=new CraftPlanner(List.of(recipe("out",1,ingredient("iron"),ingredient("wood")))).plan("out",1,Map.of("wood",1),Set.of("inventory"));assertFalse(plan.executable());assertEquals(Map.of("iron",1),plan.missing());}
    @Test void cyclicRecipesTerminate(){var plan=new CraftPlanner(List.of(recipe("a",1,ingredient("b")),recipe("b",1,ingredient("a")))).plan("a",1,Map.of(),Set.of("inventory"));assertFalse(plan.executable());assertFalse(plan.missing().isEmpty());}
    @Test void stationAvailabilityIsChecked(){var r=new CraftPlanner.Recipe("table","tool",1,List.of(ingredient("iron")),"crafting_table",Map.of());var p=new CraftPlanner(List.of(r)).plan("tool",1,Map.of("iron",1),Set.of("inventory"));assertEquals(Set.of("crafting_table"),p.missingStations());}
    @Test void remaindersAreIncluded(){var r=new CraftPlanner.Recipe("cake","cake",1,List.of(ingredient("milk_bucket")),"inventory",Map.of("milk_bucket","bucket"));var p=new CraftPlanner(List.of(r)).plan("cake",1,Map.of("milk_bucket",1),Set.of("inventory"));assertEquals(1,p.finalInventory().get("bucket"));}
    @Test void existingOutputRequiresNoMutation(){var p=new CraftPlanner(List.of()).plan("out",1,Map.of("out",1),Set.of("inventory"));assertTrue(p.executable());assertTrue(p.stages().isEmpty());}
    @Test void blueprintRotatesCoordinatesAndFacing(){var b=object("x",1,"y",0,"z",0,"type","minecraft:oak_log","properties",object("axis","x","facing","north"));var p=new BuildPlanner().plan(object("origin",object("x",10,"y",64,"z",10),"rotation",90,"blocks",List.of(b)));var block=p.blocks().getFirst();assertEquals(10,block.x());assertEquals(11,block.z());assertEquals("z",block.properties().get("axis").getAsString());assertEquals("east",block.properties().get("facing").getAsString());}
    @Test void blueprintRejectsDuplicateCoordinates(){var b=object("type","minecraft:stone");assertThrows(BridgeException.class,()->new BuildPlanner().plan(object("origin",object(),"blocks",List.of(b,b))));}
    @Test void blueprintPlacesLowestLayersFirst(){var p=new BuildPlanner().plan(object("origin",object(),"blocks",List.of(object("type","a","y",2),object("type","a","y",0))));assertEquals(0,p.blocks().getFirst().y());assertEquals(Map.of("a",2),p.materials());}
}
