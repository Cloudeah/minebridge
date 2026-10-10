package dev.minebridge.core;

import java.util.*;

/** Bounded A* over confirmed, loaded walkable cells. Never assumes an unknown cell is empty. */
public final class Pathfinder {
    public record Cell(int x,int y,int z) { public double distance(Cell b) { return Math.sqrt((double)(x-b.x)*(x-b.x)+(double)(y-b.y)*(y-b.y)+(double)(z-b.z)*(z-b.z)); } }
    public enum Terrain { WALKABLE, BLOCKED, UNKNOWN }
    public interface Grid { Terrain terrain(Cell cell); }
    public record Route(List<Cell> cells,int visited) {}
    private record Node(Cell cell,double score) {}
    public Route plan(Grid grid,Cell start,Cell goal,int budget) {
        if(start.distance(goal)>96) throw new BridgeException("path_unreachable","Route exceeds local 96-block planning limit");
        Map<Cell,Double> cost=new HashMap<>();Map<Cell,Cell> previous=new HashMap<>();Set<Cell> closed=new HashSet<>();
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        cost.put(start,0.0);open.add(new Node(start,start.distance(goal)));boolean unknown=false;
        while(!open.isEmpty() && closed.size()<budget) {
            Cell c=open.remove().cell;
            if(!closed.add(c)) continue;
            if(c.equals(goal)) { LinkedList<Cell> path=new LinkedList<>();for(Cell p=c;p!=null;p=previous.get(p))path.addFirst(p);return new Route(List.copyOf(path),closed.size()); }
            for(int[] offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                for(int dy:new int[]{0,1,-1}) {
                    Cell n=new Cell(c.x+offset[0],c.y+dy,c.z+offset[1]);
                    if(n.distance(start)>96 || closed.contains(n)) continue;
                    Terrain terrain=grid.terrain(n);if(terrain==Terrain.UNKNOWN){unknown=true;continue;}if(terrain!=Terrain.WALKABLE)continue;
                    // A step up also requires the departure cell's upper headroom.
                    if(dy==1 && grid.terrain(new Cell(c.x,c.y+1,c.z))==Terrain.UNKNOWN) {unknown=true;continue;}
                    double next=cost.get(c)+1+Math.abs(dy)*0.5;
                    if(next<cost.getOrDefault(n,Double.POSITIVE_INFINITY)) {cost.put(n,next);previous.put(n,c);open.add(new Node(n,next+n.distance(goal)));}
                    break;
                }
            }
        }
        throw new BridgeException(unknown ? "chunk_unavailable" : "path_unreachable",closed.size()>=budget ? "Path search budget exhausted" : "No confirmed walkable route",true);
    }
}
