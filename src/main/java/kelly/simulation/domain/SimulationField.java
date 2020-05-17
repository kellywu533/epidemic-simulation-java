package kelly.simulation.domain;

import kelly.simulation.MatrixUtil;
import kelly.simulation.things.Animatable;
import kelly.simulation.things.RadiatingDot;

import java.awt.*;
import java.awt.image.ImageObserver;
import java.util.*;

public class SimulationField {
    private static final long DELAY = 10;
    private static final double SUBJECT_INITIAL_MAX_VELOCITY = 1;
    private static final double MAX_RANDOM_FORCE = 2;
    public static final double DESTINATION_FORCE_FACTOR = 1;

    private static final Random random = new Random();
    private Set<SimulationEventListener> simulationListeners;
    private Set<FieldEventListener> fieldListeners;
    private ArrayList<int[]> timeData;
    private int timeIndex;
    private Subject[] subjects;
    private boolean paused = false;
    private boolean destinationOn = false;
    private int subjectCount;
    private double subjectMass;
    public double frictionFactor;
    private boolean restarting;
    private int eradicatedTime;
    private int[] hiBound;
    private int[] loBound;
    private double[] destination;
    private double oddsOfDestination;
    private int numberInitialSick;
    private int infectionRadius;
    private double oddsOfInfection;
    private int minInfectionTime;
    private int maxInfectionTime;
    private int timeScale;
    private int maxInfected;
    private int minStayTime;
    private int maxStayTime;

    private EnumMap<HealthStatus, Animatable> healthAnimation;

    public SimulationField() {
        simulationListeners = new HashSet<>();
        fieldListeners = new HashSet<>();
        assignDefaultValues();
    }

    public void init() {
        healthAnimation = new EnumMap<>(HealthStatus.class);
        healthAnimation.put(HealthStatus.SUSCEPTIBLE, new RadiatingDot(Color.BLUE, 4, 4, 1, 1));
        healthAnimation.put(HealthStatus.INFECTED, new RadiatingDot(Color.RED, 4, infectionRadius, 1, 60));
        healthAnimation.put(HealthStatus.REMOVED, new RadiatingDot(Color.GRAY, 4, 4, 1, 1));
        timeData = new ArrayList<>();
        this.subjects =  new Subject[subjectCount];
        for(int i = 0; i < subjectCount; i++) {
            Subject s = createRandomSubject();
            s.setEventTime(i);
            subjects[i] = s;
            if(i < numberInitialSick) {
                s.updateHealth(HealthStatus.INFECTED, i, randomDuration());
            }
        }
        timeIndex = subjects.length + 1;
        eradicatedTime = -1;
        maxInfected = 0;
    }

    public synchronized void assignDefaultValues() {
        subjectCount = 200;
        subjectMass = 10;
        frictionFactor = 0.98;
        hiBound = new int[] {640, 480};
        loBound = new int[] {0, 0};
        destination = new double[] {0.5 * hiBound[0], 0.5 * hiBound[1]};
        oddsOfDestination = 0.02;
        numberInitialSick = 2;
        infectionRadius = 30;
        oddsOfInfection = 0.2;
        minInfectionTime = 7;
        maxInfectionTime = 21;
        timeScale = 72;
        maxInfected = 0;
        minStayTime = 36;
        maxStayTime = 72;
        init();
        publishFieldEvent();
    }

    private int randomDuration() {
        return timeScale * (random.nextInt(maxInfectionTime - minInfectionTime) + minInfectionTime);
    }

    public void addSimulationEventListener(SimulationEventListener l) {
        simulationListeners.add(l);
    }

    public void addFieldEventListener(FieldEventListener l) {
        fieldListeners.add(l);
    }

    private void publishSimulationEvent() {
        for(SimulationEventListener l : simulationListeners) {
            l.onSimulationEvent();
        }
    }

    private void publishFieldEvent() {
        for(FieldEventListener l : fieldListeners) {
            l.onFieldEvent();
        }
    }

    public void drawSubjects(Graphics g, ImageObserver observer) {
        for(Subject s : subjects) {
            Animatable a = healthAnimation.get(s.getStatus());
            Image img = a.getFrame(timeIndex - s.getEventTime());
            double[] position = s.getPosition();
            int x = (int) (position[0] - img.getWidth(observer) / 2);
            int y = (int) (position[1] - img.getHeight(observer) / 2);
            g.drawImage(img, x, y, observer);
        }
    }

    public static void safeSleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    public synchronized void restartSimulation() {
        init();
        restarting = false;
        paused = false;
    }

    public void startSimulation() {
        restarting = true;

        while(true) {
            if(restarting) {
                restartSimulation();
            }
            if(!paused) {
                doSimulation();
            }
            safeSleep(DELAY);
        }
    }

    private synchronized void doSimulation() {
        if(destinationOn) {
            assignDestination();
        }
        updateSubjects();
        simulateTransmission();
        if(eradicatedTime <= 0) {
            doStatistics();

        }
        publishSimulationEvent();
        timeIndex++;
    }

    private void simulateTransmission() {
        double odds = oddsOfInfection / timeScale;
        int infectionRadiusSquared = infectionRadius * infectionRadius;
        for(Subject s : subjects) {
            if(HealthStatus.INFECTED.equals(s.getStatus())) {
                for(Subject s2 : subjects) {
                    if(HealthStatus.SUSCEPTIBLE.equals(s2.getStatus())
                        && random.nextDouble() < odds
                        && infectionRadiusSquared > MatrixUtil.distanceSquared(s2.getPosition(), s.getPosition())
                    ) {
                        s2.updateHealth(HealthStatus.INFECTED, timeIndex, randomDuration());
                    }
                }
            }
        }
    }

    private void assignDestination() {
        if (random.nextDouble() < oddsOfDestination) {
            int s = random.nextInt(subjects.length);
            int returnTime = timeIndex + minStayTime + random.nextInt(maxStayTime - minStayTime);
            subjects[s].assignDestination(destination, returnTime);
        }
    }

    private void updateSubjects() {
        for(Subject s : subjects) {
            s.update(subjectMass, randomVector(MAX_RANDOM_FORCE), loBound, hiBound, 1, DESTINATION_FORCE_FACTOR, timeIndex, frictionFactor);
            if(s.isTimeToChange(timeIndex)) {
                s.updateHealth(HealthStatus.REMOVED, timeIndex, -1);
            }
        }
    }

    private double[] randomVector(double max) {
        double angle = random.nextDouble() * Math.PI * 2;
        double v = random.nextDouble() * max;
        return new double[] {
                v * Math.cos(angle), v * Math.sin(angle)
        };
    }

    private double[] randomPosition() {
        int len = hiBound.length;
        double[] position = new double[len];
        for(int i = 0; i < len; i ++) {
            position[i] = random.nextDouble() * hiBound[i];
        }
        return position;
    }

    private Subject createRandomSubject() {
        return new Subject(randomPosition(), randomVector(SUBJECT_INITIAL_MAX_VELOCITY));
    }

    public int getTimeIndex() {
        return timeIndex;
    }

    public void doStatistics() {
        int[] data = new int[HealthStatus.values().length];
        for(Subject s : subjects) {
            data[s.getStatus().ordinal()]++;
        }
        timeData.add(data);
        if(data[HealthStatus.INFECTED.ordinal()]== 0) {
            eradicatedTime = getTimeIndex();
        }
        if(data[HealthStatus.INFECTED.ordinal()] > maxInfected) {
            maxInfected = data[HealthStatus.INFECTED.ordinal()];
        }
    }

    public ArrayList<int[]> getTimeData() {
        return timeData;
    }

    public int getSubjectCount() {
        return subjectCount;
    }

    public int getEradicatedTime() {
        return eradicatedTime;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void setRestarting(boolean restarting) {
        this.restarting = restarting;
    }

    public void setDestinationOn(boolean destinationOn) {
        this.destinationOn = destinationOn;
    }

    public int getMaxInfected() {
        return maxInfected;
    }

    public synchronized void updateSubjectCount(int subjects) {
        subjectCount = subjects;
        init();
    }

    public double getSubjectMass() {
        return subjectMass;
    }

    public void setSubjectMass(double subjectMass) {
        this.subjectMass = subjectMass;
    }

    public void updateHiBound(int[] highBound) {
        hiBound = highBound;
    }

    public int getNumberInitialSick() {
        return numberInitialSick;
    }

    public synchronized void getNumberInitialSick(int oddsInitialSick) {
        this.numberInitialSick = numberInitialSick;
        init();
    }

    public void updateDestination(double[] destination) {
        this.destination = destination;
    }

    public double getOddsOfDestination() {
        return oddsOfDestination;
    }

    public void setOddsOfDestination(double oddsOfDestination) {
        this.oddsOfDestination = oddsOfDestination;
    }

    public double getOddsOfInfection() {
        return oddsOfInfection;
    }

    public void setOddsOfInfection(double oddsOfInfection) {
        this.oddsOfInfection = oddsOfInfection;
    }

    public int getInfectionRadius() {
        return infectionRadius;
    }

    public synchronized void updateInfectionRadius(int infectionRadius) {
        this.infectionRadius = infectionRadius;
        init();
    }

    public int getMinInfectionTime() {
        return minInfectionTime;
    }

    public void setMinInfectionTime(int minInfectionTime) {
        this.minInfectionTime = minInfectionTime;
    }

    public int getMaxInfectionTime() {
        return maxInfectionTime;
    }

    public void setMaxInfectionTime(int maxInfectionTime) {
        this.maxInfectionTime = maxInfectionTime;
    }

    public int getMinStayTime() {
        return minStayTime;
    }

    public void setMinStayTime(int minStayTime) {
        this.minStayTime = minStayTime;
    }

    public int getMaxStayTime() {
        return maxStayTime;
    }

    public void setMaxStayTime(int maxStayTime) {
        this.maxStayTime = maxStayTime;
    }

    public double getFrictionFactor() {
        return frictionFactor;
    }

    public void setFrictionFactor(double frictionFactor) {
        this.frictionFactor = frictionFactor;
    }
}
