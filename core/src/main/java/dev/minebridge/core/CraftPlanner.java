package dev.minebridge.core;

import java.util.*;

/** Recipe alternatives are evaluated transactionally against a virtual inventory. */
public final class CraftPlanner {
    public record Ingredient(List<String> alternatives) { public Ingredient { alternatives=List.copyOf(alternatives);if(alternatives.isEmpty())throw new IllegalArgumentException("Empty ingredient"); } }
    public record Recipe(String id,String output,int count,List<Ingredient> ingredients,String station,Map<String,String> remainders) {
        public Recipe { if(count<1)throw new IllegalArgumentException("Invalid output count");ingredients=List.copyOf(ingredients);remainders=Map.copyOf(remainders); }
    }
    public record Stage(String recipe,String item,int crafts,int output,String station,List<String> ingredients) {}
    public record Plan(List<Stage> stages,Map<String,Integer> missing,Set<String> missingStations,Map<String,Integer> finalInventory,boolean executable) {}
    private final Map<String,List<Recipe>> byOutput=new HashMap<>();
    public CraftPlanner(Collection<Recipe> recipes) {for(Recipe r:recipes)byOutput.computeIfAbsent(r.output,k->new ArrayList<>()).add(r);}
    public Plan plan(String item,int count,Map<String,Integer> inventory,Set<String> stations) {
        if(count<1 || count>4096)throw new BridgeException("invalid_request","Craft count must be 1..4096");
        State s=new State(inventory,stations);ensure(item,count,s,new HashSet<>(),0);
        return new Plan(List.copyOf(s.stages),Map.copyOf(s.missing),Set.copyOf(s.missingStations),Map.copyOf(s.inventory),s.missing.isEmpty()&&s.missingStations.isEmpty());
    }
    private static final class State {
        Map<String,Integer> inventory;Set<String> stations;List<Stage> stages=new ArrayList<>();Map<String,Integer> missing=new HashMap<>();Set<String> missingStations=new HashSet<>();int budget=0;
        State(Map<String,Integer> inventory,Set<String> stations){this.inventory=new HashMap<>(inventory);this.stations=stations;}
        State copy(){State s=new State(inventory,stations);s.stages=new ArrayList<>(stages);s.missing=new HashMap<>(missing);s.missingStations=new HashSet<>(missingStations);s.budget=budget;return s;}
        void adopt(State s){inventory=s.inventory;stages=s.stages;missing=s.missing;missingStations=s.missingStations;budget=s.budget;}
        int penalty(){return missing.values().stream().mapToInt(i->i).sum()+missingStations.size()*1000;}
    }
    private void ensure(String item,int count,State s,Set<String> stack,int depth) {
        int available=s.inventory.getOrDefault(item,0);if(available>=count)return;
        int needed=count-available;
        if(depth>32 || stack.contains(item) || ++s.budget>10000) {s.missing.merge(item,needed,Integer::sum);s.inventory.merge(item,needed,Integer::sum);return;}
        List<Recipe> recipes=byOutput.getOrDefault(item,List.of());
        if(recipes.isEmpty()){s.missing.merge(item,needed,Integer::sum);s.inventory.merge(item,needed,Integer::sum);return;}
        State best=null;Set<String> nextStack=new HashSet<>(stack);nextStack.add(item);
        for(Recipe recipe:recipes) {
            State attempt=s.copy();int crafts=(needed+recipe.count-1)/recipe.count;
            if(!attempt.stations.contains(recipe.station))attempt.missingStations.add(recipe.station);
            List<String> chosen=new ArrayList<>();
            for(int k=0;k<crafts;k++) {
                List<String> batch=new ArrayList<>();
                for(Ingredient ingredient:recipe.ingredients) {
                    State ingredientBest=null;String selected=null;
                    for(String alternative:ingredient.alternatives) {
                        State option=attempt.copy();ensure(alternative,1,option,nextStack,depth+1);
                        option.inventory.merge(alternative,-1,Integer::sum);
                        if(ingredientBest==null || option.penalty()<ingredientBest.penalty()) {ingredientBest=option;selected=alternative;}
                        if(option.penalty()==attempt.penalty())break;
                    }
                    attempt.adopt(ingredientBest);batch.add(selected);
                }
                for(String consumed:batch){String remaining=recipe.remainders.get(consumed);if(remaining!=null)attempt.inventory.merge(remaining,1,Integer::sum);}
                attempt.inventory.merge(item,recipe.count,Integer::sum);chosen.addAll(batch);
            }
            attempt.stages.add(new Stage(recipe.id,item,crafts,crafts*recipe.count,recipe.station,List.copyOf(chosen)));
            if(best==null || attempt.penalty()<best.penalty())best=attempt;
            if(attempt.penalty()==s.penalty())break;
        }
        s.adopt(best);
    }
}
