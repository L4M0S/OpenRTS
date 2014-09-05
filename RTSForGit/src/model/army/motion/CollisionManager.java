/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package model.army.motion;

import model.army.data.Mover;
import geometry.AlignedBoundingBox;
import geometry.BoundingCircle;
import geometry.BoundingShape;
import geometry.Point2D;
import geometry3D.Point3D;
import java.util.ArrayList;
import math.Angle;
import model.map.Map;
import model.map.Tile;
import tools.LogUtil;

/**
 *
 * @author Benoît
 */
public class CollisionManager {
    private enum CollisionType {NONE, MAP, BLOCKER, BOTH};
    
    private static double BRAKING_RATIO = 0.9;
    private static double MAX_ADAPT_TOLERANCE = Angle.toRadians(180);
    private static double ADAPT_TOLERANCE = Angle.toRadians(100);
    private static double ADAPT_TOLERANCE_INCRASE = Angle.toRadians(20);
    private static double ADAPTATION_STEP = Angle.toRadians(1);
    
    Mover mover;
    Map map;
    ArrayList<BoundingShape> obstacles = new ArrayList<>();
    ArrayList<BoundingShape> blockers = new ArrayList<>();
    
    
    double tolerance = ADAPT_TOLERANCE;
    boolean directionIsClockwise = true;
    
    public CollisionManager(Mover m, Map map){
        this.mover = m;
        this.map = map;
    }
    
    public void applySteering(Point3D steering, double elapsedTime, ArrayList<Mover> holdingNeighbours) {
        double traveledDistance = mover.getSpeed()*elapsedTime;
        if(traveledDistance < 0.001)
            LogUtil.logger.info("very short traveled distance...");
        
        updateBlockers(holdingNeighbours);
        updateObstacles();
        
        if(steering.isOrigin())
            brake(elapsedTime);
        else {
            Point3D newVelocity = mover.velocity.getAddition(steering).getTruncation(traveledDistance);
            if(mover.fly())
                mover.velocity = newVelocity;
            else
                mover.velocity = adaptVelocity(newVelocity);
 
            if(mover.hasDestination())
                mover.velocity = mover.velocity.getTruncation(mover.pos.get2D().getDistance(mover.getDestination()));
        }
        mover.pos = mover.pos.getAddition(mover.velocity);
    }
    
    public void brake(double elapsedTime) {
        try {
            Point3D brakeForce = mover.velocity.getNegation().getMult(BRAKING_RATIO);
            brakeForce.getTruncation(elapsedTime);
            mover.velocity = mover.velocity.getAddition(brakeForce);
        } catch(RuntimeException e){
            LogUtil.logger.info("erreur dans le brake : "+mover.velocity+" ; elapsed time : "+elapsedTime);
        }
        
        if(mover.velocity.getNorm()<0.01)
            mover.velocity = Point3D.ORIGIN;
    }
    
    private Point3D adaptVelocity(Point3D velocity){
        // this first case test if the mover is already colliding something
        if(willCollide(Point3D.ORIGIN))
            return fleeOverlap(velocity);
        
        if(willCollideObstacles(velocity))
            return findNearestDirection(velocity);
        
        if(willCollideBlockers(velocity))
            if(tolerance == ADAPT_TOLERANCE)
                return findNearestDirection(velocity);
            else
                return followLastDirection(velocity);
        
        tolerance = Math.max(tolerance-1, ADAPT_TOLERANCE);
        return velocity;
    }
    
    private void changeDirection(){
        directionIsClockwise = !directionIsClockwise;
        tolerance += ADAPT_TOLERANCE_INCRASE;
        if(tolerance > MAX_ADAPT_TOLERANCE)
            giveUp();
    }
    
    private Point3D fleeOverlap(Point3D velocity){
        Point3D fleeingVector = Point3D.ORIGIN;
        BoundingShape shape = mover.getBounds();
        ArrayList<BoundingShape> allObstacles = new ArrayList<>();
        allObstacles.addAll(obstacles);
        allObstacles.addAll(blockers);
        for(BoundingShape s : allObstacles)
            if(shape.collide(s))
                fleeingVector = fleeingVector.getAddition(shape.getCenter().getSubtraction(s.getCenter()).get3D(0));
        return fleeingVector.getTruncation(velocity.getNorm());
    }
    
    private Point3D findNearestDirection(Point3D velocity){
        int count = 0;
        Point2D clockwiseTry = new Point2D(velocity);
        Point2D counterclockwiseTry = new Point2D(velocity);
        while(true){
            clockwiseTry = clockwiseTry.getRotation(-ADAPTATION_STEP);
            if(!willCollide(clockwiseTry.get3D(0))){
                directionIsClockwise = true;
                return clockwiseTry.get3D(velocity.z);
            }

            counterclockwiseTry = counterclockwiseTry.getRotation(ADAPTATION_STEP);
            if(!willCollide(counterclockwiseTry.get3D(0))){
                directionIsClockwise = false;
                return counterclockwiseTry.get3D(velocity.z);
            }

            if(count++ > tolerance/ADAPTATION_STEP){
                giveUp();
                return Point3D.ORIGIN;
            }
        }
    }
    
    private Point3D followLastDirection(Point3D velocity){
        int count = 0;
        Point2D triedVelocity = new Point2D(velocity);
        while(true){
            if(directionIsClockwise)
                triedVelocity = triedVelocity.getRotation(-ADAPTATION_STEP);
            else
                triedVelocity = triedVelocity.getRotation(ADAPTATION_STEP);

            if(!willCollide(triedVelocity.get3D(0)))
                return triedVelocity.get3D(velocity.z);

            if(count++ > tolerance/ADAPTATION_STEP) {
                changeDirection();
                return Point3D.ORIGIN;
            }
        }
    }
    
    private void updateObstacles(){
        obstacles.clear();
        for(int x = -2; x<3; x++)
            for(int y = -2; y<3; y++){
                Point2D tilePos = mover.getPos2D().getAddition(x, y);
                if(!map.isInBounds(tilePos))
                    continue;
                Tile t = map.getTile(tilePos);
                if(t.isCliff())
                    obstacles.add(t.getBounds());
            }
    }
    
    private void updateBlockers(ArrayList<Mover> holdingUnits){
        blockers.clear();
        for(Mover m : holdingUnits)
            if(mover.getDistance(m)<mover.getSpacing(m))
                blockers.add(m.getBounds());
    }
    
    
    private boolean willCollide(Point3D velocity){
        return willCollideBlockers(velocity) || willCollideObstacles(velocity);
    }
    private boolean willCollideObstacles(Point3D velocity){
        BoundingShape futurShape = getFuturShape(velocity);
        if(!map.isInBounds(((BoundingCircle)futurShape).center))
            return true;
        return futurShape.collide(obstacles);
    }

    private boolean willCollideBlockers(Point3D velocity) {
        BoundingShape futurShape = getFuturShape(velocity);
        return futurShape.collide(blockers);
    }
    
    private BoundingShape getFuturShape(Point3D velocity){
        return new BoundingCircle(mover.pos.getAddition(velocity).get2D(), mover.movable.getRadius());
    }
    
    private void giveUp(){
//        LogUtil.logger.info("stuck de chez stuck");
        tolerance = ADAPT_TOLERANCE;
        mover.setDestinationReached();
    }
}
