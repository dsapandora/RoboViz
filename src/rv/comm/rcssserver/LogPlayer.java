/*
 *  Copyright 2011 RoboViz
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package rv.comm.rcssserver;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import js.math.Maths;
import rv.Configuration;
import rv.util.StringUtil;
import rv.util.observer.IObserver;
import rv.util.observer.ISubscribe;
import rv.util.observer.Subject;
import rv.world.WorldModel;

/**
 * Reads simulation messages from a logfile instead of rcssserver3d
 * 
 * @author justin
 * 
 */
public class LogPlayer implements ISubscribe<Boolean> {

    private static final int       MS_PER_MESSAGE     = 200;
    private static final int       GOAL_WINDOW_FRAMES = 60;

    private final Configuration    config;
    private ILogfileReader         logfile;
    private LogRunnerThread        logRunner;
    private FindGoalsThread        goalFinder;
    private final MessageParser    parser;
    private boolean                playing;
    private double                 playbackSpeed      = 1;
    private Integer                desiredFrame       = null;
    private List<Integer>          goalFrames         = new ArrayList<>();
    private boolean                goalsProcessed     = false;

    /** the list of observers that are informed if something changes */
    private final Subject<Boolean> observers;

    /** file chooser for opening logfiles (instance attribute to stay in the selected path) */
    private final JFileChooser     fileChooser;

    /**
     * Default constructor. Opens the passed logfile and starts playing.
     * 
     * @param file
     *            the logfile to open. Supported are log, zipped logs and tar.bz2
     * @param world
     *            reference to the world model
     */
    public LogPlayer(File file, WorldModel world, Configuration config) {
        this.config = config;

        observers = new Subject<>();
        playing = false;
        fileChooser = new JFileChooser();
        parser = new MessageParser(world);

        if (file == null)
            return;

        openLogfile(file);

        if (!logfile.isValid()) {
            System.out.println("Logfile could not be loaded.");
            return;
        }

        startRunnerThread();
    }

    public void setWorldModel(WorldModel world) {
        parser.setWorldModel(world);
    }

    public boolean isValid() {
        return logfile != null && logfile.isValid();
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isAtEnd() {
        return logfile != null && logfile.isAtEndOfLog();
    }

    public int getDesiredFrame() {
        return desiredFrame == null ? getFrame() : desiredFrame;
    }

    private int getFrame() {
        if (logfile == null) {
            return 0;
        }
        return logfile.getCurrentFrame();
    }

    public int getNumFrames() {
        if (logfile == null) {
            return 0;
        }
        return logfile.getNumFrames();
    }

    public void pause() {
        setPlaying(false);
    }

    public void resume() {
        setPlaying(true);
    }

    public void rewind() {
        setDesiredFrame(0);
    }

    public void setPlayBackSpeed(double factor) {
        playbackSpeed = Maths.clamp(factor, -10, 10);
        observers.onStateChange(playing);
    }

    public void increasePlayBackSpeed() {
        setPlayBackSpeed(getPlayBackSpeed() + 0.25);
    }

    public void decreasePlayBackSpeed() {
        setPlayBackSpeed(getPlayBackSpeed() - 0.25);
    }

    public double getPlayBackSpeed() {
        return playbackSpeed;
    }

    private void parseFrame() throws ParseException {
        String msg = logfile.getCurrentFrameMessage();
        if (msg != null)
            parser.parse(msg);
    }

    public void stepBackward() {
        try {
            logfile.stepBackward();
            parseFrame();
            observers.onStateChange(playing);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stepForward() {
        try {
            logfile.stepForward();
            parseFrame();
            observers.onStateChange(playing);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stepBackwardGoal() {
        int relativeFrame = getDesiredFrame();
        int closestFrame = -1;
        for (Integer goalFrame : getGoalFrames()) {
            if (goalFrame < relativeFrame && goalFrame > closestFrame) {
                closestFrame = goalFrame;
            }
        }
        if (closestFrame != -1) {
            setDesiredFrame(closestFrame);
        }
    }

    public void stepForwardGoal() {
        int relativeFrame = getDesiredFrame() + GOAL_WINDOW_FRAMES;
        int closestFrame = Integer.MAX_VALUE;
        for (Integer goalFrame : getGoalFrames()) {
            if (goalFrame > relativeFrame && goalFrame < closestFrame) {
                closestFrame = goalFrame;
            }
        }
        if (closestFrame != Integer.MAX_VALUE) {
            setDesiredFrame(closestFrame);
        }
    }

    public boolean hasPreviousGoal() {
        if (goalFrames.isEmpty())
            return false;

        for (Integer goalFrame : getGoalFrames()) {
            if (getDesiredFrame() > goalFrame)
                return true;
        }
        return false;
    }

    public boolean hasNextGoal() {
        if (goalFrames.isEmpty())
            return false;

        for (Integer goalFrame : getGoalFrames()) {
            if (getDesiredFrame() < goalFrame)
                return true;
        }
        return false;
    }

    public String getPreviousGoalMessage() {
        return formatGoalMessage(getPreviousGoalNumber(), "previous");
    }

    public String getNextGoalMessage() {
        return formatGoalMessage(getNextGoalNumber(), "next");
    }

    private String formatGoalMessage(Integer targetGoalFrame, String direction) {
        if ((goalFrames.isEmpty() && goalsProcessed()) || targetGoalFrame == null) {
            return "No " + direction + " goals";
        }
        return StringUtil.capitalize(direction) + " goal: " + targetGoalFrame + "/"
                + goalFrames.size();
    }

    private Integer getPreviousGoalNumber() {
        Integer previousGoalNumber = null;
        List<Integer> syncGoalFrames = getGoalFrames();
        for (int i = 0; i < syncGoalFrames.size(); i++) {
            if (getDesiredFrame() > syncGoalFrames.get(i)) {
                previousGoalNumber = i + 1;
            }
        }
        return previousGoalNumber;
    }

    private Integer getNextGoalNumber() {
        Integer nextGoalNumber = null;
        List<Integer> syncGoalFrames = getGoalFrames();
        for (int i = 0; i < syncGoalFrames.size(); i++) {
            if (getDesiredFrame() < syncGoalFrames.get(i)) {
                nextGoalNumber = i + 1;
                break;
            }
        }
        return nextGoalNumber;
    }

    private List<Integer> getGoalFrames() {
        return goalsProcessed ? goalFrames : Collections.synchronizedList(goalFrames);
    }

    public boolean goalsProcessed() {
        return goalsProcessed;
    }

    public void setDesiredFrame(int frame) {
        desiredFrame = frame;
        observers.onStateChange(playing);
    }

    @Override
    public void attach(IObserver<Boolean> observer) {
        observers.attach(observer);
        observers.onStateChange(false);
    }

    private void setPlaying(boolean playing) {
        if (playing != this.playing) {
            this.playing = playing;
            observers.onStateChange(playing);
        }
    }

    /**
     * Allows the user to choose a logfile to open.
     */
    public void openFile(JFrame parent) {
        String logfileDirectory = config.general.logfileDirectory;
        if (logfileDirectory != null && !logfileDirectory.isEmpty()) {
            fileChooser.setCurrentDirectory(new File(logfileDirectory));
        }
        int returnVal = fileChooser.showOpenDialog(parent);
        if (returnVal == JFileChooser.CANCEL_OPTION) {
            return;
        }

        File logFile = fileChooser.getSelectedFile();
        if (logFile.exists()) {
            openLogfile(logFile);
            resume();
            startRunnerThread();
        }
    }

    /**
     * Creates a new instance of a buffered logfile reader representing the passed file.
     * 
     * @param file
     *            the logfile to open
     */
    private void openLogfile(File file) {
        try {
            if (logfile != null) {
                logfile.close();
                desiredFrame = null;
                getGoalFrames().clear();
                goalsProcessed = false;
            }
            logfile = new LogfileReaderBuffered(new Logfile(file), 200);
            startGoalFinder(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startRunnerThread() {
        if (logRunner != null) {
            logRunner.abort();
        }
        logRunner = new LogRunnerThread();
        logRunner.start();
    }

    private void startGoalFinder(File file) {
        if (goalFinder != null) {
            goalFinder.abort();
        }
        goalFinder = new FindGoalsThread(file, new FindGoalsThread.ResultCallback() {
            @Override
            public void goalFound(int goalFrame) {
                goalFrame = Math.max(0, goalFrame - GOAL_WINDOW_FRAMES);
                goalFrames.add(goalFrame);
                observers.onStateChange(playing);
            }

            @Override
            public void finished() {
                LogPlayer.this.goalsProcessed = true;
            }
        });
        goalFinder.start();
    }

    private class LogRunnerThread extends Thread {

        private boolean aborted;

        public void abort() {
            this.aborted = true;
        }

        @Override
        public void run() {
            setPlaying(true);

            while (!aborted) {
                if (logfile.isAtEndOfLog())
                    pause();

                int previousFrame = getFrame();
                int nextFrame = getFrame();
                try {
                    if (playing && playbackSpeed != 0) {
                        Thread.sleep(Math.abs((int) (MS_PER_MESSAGE / playbackSpeed)));
                        if (playbackSpeed > 0)
                            nextFrame++;
                        else
                            nextFrame--;
                    } else {
                        Thread.sleep(MS_PER_MESSAGE);
                    }

                    if (desiredFrame != null) {
                        nextFrame = desiredFrame;
                        desiredFrame = null;
                    }
                } catch (InterruptedException e) {
                }

                setCurrentFrame(previousFrame, nextFrame);
                observers.onStateChange(playing);
            }
        }

        private void setCurrentFrame(int previousFrame, int frame) {
            if (frame == getFrame()) {
                return;
            }

            try {
                if (previousFrame + 1 == frame) {
                    logfile.stepForward();
                    parseFrame();
                } else {
                    stepAnywhere(frame);
                }
            } catch (Exception e) {
            }
        }

        private void stepAnywhere(int frame) throws ParseException, IOException {
            // when jumping forwards we have to make sure not to jump over a full frame
            int currentFrame = frame;
            boolean needHeader = true;
            do {
                logfile.stepAnywhere(currentFrame);
                try {
                    parseFrame();
                    needHeader = false;
                } catch (IndexOutOfBoundsException e) {
                    // the frame misses some information from the last full frame
                    // DIRTY: I have no idea currently how this can be detected in a
                    // nicer way
                }
                currentFrame--;
            } while (needHeader && currentFrame >= 0);
        }
    }
}
