/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.gui.util;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.*;
import javafx.scene.effect.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Transitions {
    private static final Logger log = LoggerFactory.getLogger(Transitions.class);

    public static final int DURATION = 400;

    public static void fadeIn(Node node) {
        fadeIn(node, DURATION);
    }

    public static void fadeIn(Node node, int duration) {
        FadeTransition ft = new FadeTransition(Duration.millis(duration), node);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);
        ft.play();
    }

    public static Animation fadeOut(Node node) {
        return fadeOut(node, DURATION);
    }

    public static Animation fadeOut(Node node, int duration) {
        FadeTransition ft = new FadeTransition(Duration.millis(duration), node);
        ft.setFromValue(node.getOpacity());
        ft.setToValue(0.0);
        ft.play();
        return ft;
    }

    public static Animation fadeOutAndRemove(Node node) {
        return fadeOutAndRemove(node, DURATION);
    }

    public static Animation fadeOutAndRemove(Node node, int duration) {
        Animation animation = fadeOut(node, duration);
        animation.setOnFinished(actionEvent -> {
            ((Pane) (node.getParent())).getChildren().remove(node);
            Profiler.printMsgWithTime("fadeOutAndRemove");
        });
        return animation;
    }

    public static void blur(Node node) {
        blur(node, DURATION, true, false);
    }

    public static Timeline blur(Node node, int duration, boolean useDarken, boolean removeNode) {
        GaussianBlur blur = new GaussianBlur(0.0);
        Timeline timeline = new Timeline();
        KeyValue kv1 = new KeyValue(blur.radiusProperty(), 15.0);
        KeyFrame kf1 = new KeyFrame(Duration.millis(duration), kv1);

        if (useDarken) {
            ColorAdjust darken = new ColorAdjust();
            darken.setBrightness(0.0);
            blur.setInput(darken);

            KeyValue kv2 = new KeyValue(darken.brightnessProperty(), -0.3);
            KeyFrame kf2 = new KeyFrame(Duration.millis(duration), kv2);
            timeline.getKeyFrames().addAll(kf1, kf2);
        }
        else {
            timeline.getKeyFrames().addAll(kf1);
        }
        node.setEffect(blur);
        if (removeNode) timeline.setOnFinished(actionEvent -> ((Pane) (node.getParent())).getChildren().remove(node));
        timeline.play();
        return timeline;
    }

    public static void removeBlur(Node node) {
        removeBlur(node, DURATION, false);
    }

    public static void removeBlur(Node node, int duration, boolean useDarken) {
        GaussianBlur blur = (GaussianBlur) node.getEffect();
        Timeline timeline = new Timeline();

        KeyValue kv1 = new KeyValue(blur.radiusProperty(), 0.0);
        KeyFrame kf1 = new KeyFrame(Duration.millis(DURATION), kv1);


        if (useDarken) {
            ColorAdjust darken = (ColorAdjust) blur.getInput();

            KeyValue kv2 = new KeyValue(darken.brightnessProperty(), 0.0);
            KeyFrame kf2 = new KeyFrame(Duration.millis(duration), kv2);
            timeline.getKeyFrames().addAll(kf1, kf2);
        }
        else {
            timeline.getKeyFrames().addAll(kf1);
        }

        timeline.setOnFinished(actionEvent -> node.setEffect(null));
        timeline.play();
    }
}
