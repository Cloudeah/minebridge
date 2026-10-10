package dev.minebridge.mod;

import dev.minebridge.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.ItemStack;
import java.util.*;

final class RecipeCatalog {
    record Entry(CraftPlanner.Recipe recipe,int width,int height,List<List<String>> grid) {}
    final Map<String,Entry> entries=new LinkedHashMap<>();
    static RecipeCatalog load(MinecraftServer server) {
        RecipeCatalog catalog=new RecipeCatalog();
        for(var holder:server.getRecipeManager().recipeMap().byType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe=holder.value();
            if(!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe))continue;
            var placement=recipe.placementInfo();
            if(placement.isImpossibleToPlace() || placement.ingredients().stream().anyMatch(i->!i.isSimple()))continue;
            int width=recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : (placement.ingredients().size()<=4 ? 2 : 3);
            int height=recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : (placement.ingredients().size()+width-1)/width;
            if(width>3 || height>3)continue;
            List<List<String>> grid=new ArrayList<>();List<CraftPlanner.Ingredient> ingredients=new ArrayList<>();List<ItemStack> sample=new ArrayList<>();Map<String,String> remainders=new HashMap<>();boolean supported=true;
            for(int cell=0;cell<width*height;cell++) {
                int idx=cell<placement.slotsToIngredientIndex().size()?placement.slotsToIngredientIndex().getInt(cell):-1;
                if(idx<0){grid.add(List.of());sample.add(ItemStack.EMPTY);continue;}
                Ingredient ingredient=placement.ingredients().get(idx);
                var items=ingredient.items().toList();
                if(items.isEmpty()){supported=false;break;}
                List<String> ids=items.stream().map(i->BuiltInRegistries.ITEM.getKey(i.value()).toString()).toList();
                grid.add(ids);ingredients.add(new CraftPlanner.Ingredient(ids));sample.add(items.getFirst().value().getDefaultInstance());
                for(var item:items) {
                    var remainder=item.value().getCraftingRemainder();
                    if(remainder!=null){if(remainder.count()!=1)supported=false;else remainders.put(BuiltInRegistries.ITEM.getKey(item.value()).toString(),BuiltInRegistries.ITEM.getKey(remainder.item().value()).toString());}
                }
            }
            if(!supported)continue;
            ItemStack output=recipe.assemble(CraftingInput.of(width,height,sample));if(output.isEmpty())continue;
            String id=holder.id().identifier().toString(),item=BuiltInRegistries.ITEM.getKey(output.getItem()).toString();
            String station=width<=2 && height<=2 ? "inventory" : "crafting_table";
            catalog.entries.put(id,new Entry(new CraftPlanner.Recipe(id,item,output.getCount(),ingredients,station,remainders),width,height,List.copyOf(grid)));
        }
        return catalog;
    }
    CraftPlanner planner(){return new CraftPlanner(entries.values().stream().map(Entry::recipe).toList());}
}
