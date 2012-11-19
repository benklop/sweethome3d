/*
 * HomeController3D.java 21 juin 07
 *
 * Sweet Home 3D, Copyright (c) 2007 Emmanuel PUYBARET / eTeks <info@eteks.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.eteks.sweethome3d.viewcontroller;

import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.swing.undo.UndoableEditSupport;

import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.CollectionEvent;
import com.eteks.sweethome3d.model.CollectionListener;
import com.eteks.sweethome3d.model.Elevatable;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeEnvironment;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.ObserverCamera;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.SelectionEvent;
import com.eteks.sweethome3d.model.SelectionListener;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.model.Wall;

/**
 * A MVC controller for the home 3D view.
 * @author Emmanuel Puybaret
 */
public class HomeController3D implements Controller {
  private final Home                  home;
  private final UserPreferences       preferences;
  private final ViewFactory           viewFactory;
  private final ContentManager        contentManager;
  private final UndoableEditSupport   undoSupport;
  private View                        home3DView;
  // Possibles states
  private final CameraControllerState topCameraState;
  private final CameraControllerState observerCameraState;
  // Current state
  private CameraControllerState       cameraState;

  /**
   * Creates the controller of home 3D view.
   * @param home the home edited by this controller and its view
   */
  public HomeController3D(final Home home, 
                          UserPreferences preferences,
                          ViewFactory viewFactory, 
                          ContentManager contentManager, 
                          UndoableEditSupport undoSupport) {
    this.home = home;
    this.preferences = preferences;
    this.viewFactory = viewFactory;
    this.contentManager = contentManager;
    this.undoSupport = undoSupport;
    // Initialize states
    this.topCameraState = new TopCameraState(preferences);
    this.observerCameraState = new ObserverCameraState();
    // Set default state 
    setCameraState(home.getCamera() == home.getTopCamera() 
        ? this.topCameraState
        : this.observerCameraState);
    addModelListeners(home);
  }

  /**
   * Add listeners to model to update camera position accordingly.
   */
  private void addModelListeners(final Home home) {
    home.addPropertyChangeListener(Home.Property.CAMERA, new PropertyChangeListener() {      
        public void propertyChange(PropertyChangeEvent ev) {
          setCameraState(home.getCamera() == home.getTopCamera() 
              ? topCameraState
              : observerCameraState);
        }
      });
    // Add listeners to adjust observer camera elevation when the elevation of the selected level  
    // or the level selection change
    final PropertyChangeListener levelElevationChangeListener = new PropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent ev) {
          if (Level.Property.ELEVATION.name().equals(ev.getPropertyName()) 
              && home.getEnvironment().isObserverCameraElevationAdjusted()) {
            home.getObserverCamera().setZ(Math.max(getObserverCameraMinimumElevation(home), 
                home.getObserverCamera().getZ() + (Float)ev.getNewValue() - (Float)ev.getOldValue()));
          }
        }
      };
    Level selectedLevel = home.getSelectedLevel();
    if (selectedLevel != null) {
      selectedLevel.addPropertyChangeListener(levelElevationChangeListener);
    }
    this.home.addPropertyChangeListener(Home.Property.SELECTED_LEVEL, new PropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent ev) {
          Level oldSelectedLevel = (Level)ev.getOldValue();
          Level selectedLevel = home.getSelectedLevel();
          if (home.getEnvironment().isObserverCameraElevationAdjusted()) {
            home.getObserverCamera().setZ(Math.max(getObserverCameraMinimumElevation(home), 
                home.getObserverCamera().getZ() 
                + (selectedLevel == null ? 0 : selectedLevel.getElevation()) 
                - (oldSelectedLevel == null ? 0 : oldSelectedLevel.getElevation())));
          }
          if (oldSelectedLevel != null) {
            oldSelectedLevel.removePropertyChangeListener(levelElevationChangeListener);
          }
          if (selectedLevel != null) {
            selectedLevel.addPropertyChangeListener(levelElevationChangeListener);
          }
        }
      });     
    // Add a listener to home to update visible levels according to selected level
    PropertyChangeListener selectedLevelListener = new PropertyChangeListener() {
         public void propertyChange(PropertyChangeEvent ev) {
           List<Level> levels = home.getLevels();
           Level selectedLevel = home.getSelectedLevel();
           boolean visible = true;
           for (int i = 0; i < levels.size(); i++) {
             levels.get(i).setVisible(visible);
             if (levels.get(i) == selectedLevel
                 && !home.getEnvironment().isAllLevelsVisible()) {
               visible = false;
             }
           }
         }
       };
     this.home.addPropertyChangeListener(Home.Property.SELECTED_LEVEL, selectedLevelListener);     
     this.home.getEnvironment().addPropertyChangeListener(HomeEnvironment.Property.ALL_LEVELS_VISIBLE, selectedLevelListener);
  }

  private float getObserverCameraMinimumElevation(final Home home) {
    List<Level> levels = home.getLevels();
    float minimumElevation = levels.size() == 0  ? 10  : 10 + levels.get(0).getElevation();
    return minimumElevation;
  }

  /**
   * Returns the view associated with this controller.
   */
  public View getView() {
    // Create view lazily only once it's needed
    if (this.home3DView == null) {
      this.home3DView = this.viewFactory.createView3D(this.home, this.preferences, this);
    }
    return this.home3DView;
  }

  /**
   * Changes home camera for {@link Home#getTopCamera() top camera}.
   */
  public void viewFromTop() {
    this.home.setCamera(this.home.getTopCamera());
  }
  
  /**
   * Changes home camera for {@link Home#getObserverCamera() observer camera}.
   */
  public void viewFromObserver() {
    this.home.setCamera(this.home.getObserverCamera());
  }
  
  /**
   * Stores a clone of the current camera in home under the given <code>name</code>.
   */
  public void storeCamera(String name) {
    Camera camera = this.home.getCamera().clone();
    camera.setName(name);
    List<Camera> homeStoredCameras = this.home.getStoredCameras();
    ArrayList<Camera> storedCameras = new ArrayList<Camera>(homeStoredCameras.size() + 1);
    storedCameras.addAll(homeStoredCameras);
    // Don't keep two cameras with the same name or the same location
    for (Iterator<Camera> it = storedCameras.iterator(); it.hasNext(); ) {
      Camera storedCamera = it.next();
      if (name.equals(storedCamera.getName())
          || (camera.getX() == storedCamera.getX()
              && camera.getY() == storedCamera.getY()
              && camera.getZ() == storedCamera.getZ()
              && camera.getPitch() == storedCamera.getPitch()
              && camera.getYaw() == storedCamera.getYaw()
              && camera.getFieldOfView() == storedCamera.getFieldOfView()
              && camera.getTime() == storedCamera.getTime()
              && camera.getLens() == storedCamera.getLens())) {
        it.remove();
      }
    }
    storedCameras.add(0, camera);
    // Ensure home stored cameras don't contain more than 20 cameras
    while (storedCameras.size() > 20) {
      storedCameras.remove(storedCameras.size() - 1);
    }
    this.home.setStoredCameras(storedCameras);
  }
  
  /**
   * Switches to observer or top camera and move camera to the values as the current camera.
   */
  public void goToCamera(Camera camera) {
    if (camera instanceof ObserverCamera) {
      viewFromObserver();
    } else {
      viewFromTop();
    }
    this.cameraState.goToCamera(camera);
    // Reorder cameras
    ArrayList<Camera> storedCameras = new ArrayList<Camera>(this.home.getStoredCameras());
    storedCameras.remove(camera);
    storedCameras.add(0, camera);
    this.home.setStoredCameras(storedCameras);
  }
  
  /**
   * Makes all levels visible.
   */
  public void displayAllLevels() {
    this.home.getEnvironment().setAllLevelsVisible(true);
  }
  
  /**
   * Makes the selected level and below visible.
   */
  public void displaySelectedLevel() {
    this.home.getEnvironment().setAllLevelsVisible(false);
  }
  
  /**
   * Controls the edition of 3D attributes. 
   */
  public void modifyAttributes() {
    new Home3DAttributesController(this.home, this.preferences, 
        this.viewFactory, this.contentManager, this.undoSupport).displayView(getView());    
  }

  /**
   * Changes current state of controller.
   */
  protected void setCameraState(CameraControllerState state) {
    if (this.cameraState != null) {
      this.cameraState.exit();
    }
    this.cameraState = state;
    this.cameraState.enter();
  }
  
  /**
   * Moves home camera of <code>delta</code>.
   */
  public void moveCamera(float delta) {
    this.cameraState.moveCamera(delta);
  }

  /**
   * Elevates home camera of <code>delta</code>.
   */
  public void elevateCamera(float delta) {
    this.cameraState.elevateCamera(delta);
  }

  /**
   * Rotates home camera yaw angle of <code>delta</code> radians.
   */
  public void rotateCameraYaw(float delta) {
    this.cameraState.rotateCameraYaw(delta);
  }

  /**
   * Rotates home camera pitch angle of <code>delta</code> radians.
   */
  public void rotateCameraPitch(float delta) {
    this.cameraState.rotateCameraPitch(delta);
  }

  /**
   * Returns the observer camera state.
   */
  protected CameraControllerState getObserverCameraState() {
    return this.observerCameraState;
  }

  /**
   * Returns the top camera state.
   */
  protected CameraControllerState getTopCameraState() {
    return this.topCameraState;
  }

  /**
   * Controller state classes super class.
   */
  protected static abstract class CameraControllerState {
    public void enter() {
    }

    public void exit() {
    }

    public void moveCamera(float delta) {
    }

    public void elevateCamera(float delta) {     
    }
    
    public void rotateCameraYaw(float delta) {
    }

    public void rotateCameraPitch(float delta) {
    }

    public void goToCamera(Camera camera) {
    }
  }
  
  // CameraControllerState subclasses

  /**
   * Top camera controller state. 
   */
  private class TopCameraState extends CameraControllerState {
    private final Rectangle2D MIN_BOUNDS = new Rectangle2D.Float(0, 0, 1000, 1000);
    
    private Camera      topCamera;
    private Rectangle2D homeBounds;
    private float       minDistanceToHomeCenter;
    private float       maxDistanceToHomeCenter;
    private boolean     aerialViewCenteredOnSelectionEnabled;
    private PropertyChangeListener levelVisibilityChangeListener = new PropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent ev) {
          if (Level.Property.VISIBLE.name().equals(ev.getPropertyName())) {
            updateCameraFromHomeBounds();
          }
        }
      };
    private CollectionListener<Level> levelsListener = new CollectionListener<Level>() {
        public void collectionChanged(CollectionEvent<Level> ev) {
          if (ev.getType() == CollectionEvent.Type.ADD) {
            ev.getItem().addPropertyChangeListener(levelVisibilityChangeListener);
          } else if (ev.getType() == CollectionEvent.Type.DELETE) {
            ev.getItem().removePropertyChangeListener(levelVisibilityChangeListener);
          } 
          updateCameraFromHomeBounds();
        }
      };
    private PropertyChangeListener objectChangeListener = new PropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent ev) {
          updateCameraFromHomeBounds();
        }
      };
    private CollectionListener<Wall> wallsListener = new CollectionListener<Wall>() {
        public void collectionChanged(CollectionEvent<Wall> ev) {
          if (ev.getType() == CollectionEvent.Type.ADD) {
            ev.getItem().addPropertyChangeListener(objectChangeListener);
          } else if (ev.getType() == CollectionEvent.Type.DELETE) {
            ev.getItem().removePropertyChangeListener(objectChangeListener);
          } 
          updateCameraFromHomeBounds();
        }
      };
    private CollectionListener<HomePieceOfFurniture> furnitureListener = new CollectionListener<HomePieceOfFurniture>() {
        public void collectionChanged(CollectionEvent<HomePieceOfFurniture> ev) {
          if (ev.getType() == CollectionEvent.Type.ADD) {
            ev.getItem().addPropertyChangeListener(objectChangeListener);
          } else if (ev.getType() == CollectionEvent.Type.DELETE) {
            ev.getItem().removePropertyChangeListener(objectChangeListener);
          } 
          updateCameraFromHomeBounds();
        }
      };
    private CollectionListener<Room> roomsListener = new CollectionListener<Room>() {
        public void collectionChanged(CollectionEvent<Room> ev) {
          if (ev.getType() == CollectionEvent.Type.ADD) {
            ev.getItem().addPropertyChangeListener(objectChangeListener);
          } else if (ev.getType() == CollectionEvent.Type.DELETE) {
            ev.getItem().removePropertyChangeListener(objectChangeListener);
          } 
          updateCameraFromHomeBounds();
        }
      };
    private SelectionListener selectionListener = new SelectionListener() {
        public void selectionChanged(SelectionEvent ev) {
          updateCameraFromHomeBounds();
        }
      };

    public TopCameraState(UserPreferences preferences) {
      this.aerialViewCenteredOnSelectionEnabled = preferences.isAerialViewCenteredOnSelectionEnabled();
      preferences.addPropertyChangeListener(UserPreferences.Property.AERIAL_VIEW_CENTERED_ON_SELECTION_ENABLED, 
          new UserPreferencesChangeListener(this));
    }

    @Override
    public void enter() {
      this.topCamera = home.getCamera();
      updateCameraFromHomeBounds();
      for (Level level : home.getLevels()) {
        level.addPropertyChangeListener(this.levelVisibilityChangeListener);
      }
      home.addLevelsListener(this.levelsListener);
      for (Wall wall : home.getWalls()) {
        wall.addPropertyChangeListener(this.objectChangeListener);
      }
      home.addWallsListener(this.wallsListener);
      for (HomePieceOfFurniture piece : home.getFurniture()) {
        piece.addPropertyChangeListener(this.objectChangeListener);
      }
      home.addFurnitureListener(this.furnitureListener);
      for (Room room : home.getRooms()) {
        room.addPropertyChangeListener(this.objectChangeListener);
      }
      home.addRoomsListener(this.roomsListener);
      home.addSelectionListener(this.selectionListener);
    }
    
    /**
     * Sets whether aerial view should be centered on selection or not.
     */
    public void setAerialViewCenteredOnSelectionEnabled(boolean aerialViewCenteredOnSelectionEnabled) {
      this.aerialViewCenteredOnSelectionEnabled = aerialViewCenteredOnSelectionEnabled;
      updateCameraFromHomeBounds();
    }
    
    /**
     * Updates camera location from home bounds.
     */
    private void updateCameraFromHomeBounds() {
      if (this.homeBounds == null) {
        this.homeBounds = getHomeBounds(this.aerialViewCenteredOnSelectionEnabled);
      }
      float distanceToCenter = (float)Math.sqrt(Math.pow(this.homeBounds.getCenterX() - this.topCamera.getX(), 2) 
          + Math.pow(this.homeBounds.getCenterY() - this.topCamera.getY(), 2) 
          + Math.pow(this.topCamera.getZ(), 2));
      this.homeBounds = getHomeBounds(this.aerialViewCenteredOnSelectionEnabled);
      this.minDistanceToHomeCenter = getMinDistanceToHomeCenter(this.homeBounds, this.aerialViewCenteredOnSelectionEnabled);
      this.maxDistanceToHomeCenter = getMaxDistanceToHomeCenter(this.homeBounds, this.minDistanceToHomeCenter);
      placeCameraAt(distanceToCenter);
    }

    /**
     * Returns home bounds that includes walls, furniture and rooms.
     */
    private Rectangle2D getHomeBounds(boolean centerOnSelection) {
      List<Selectable> selectedItems = home.getSelectedItems();
      boolean selectionEmpty = selectedItems.size() == 0 || !centerOnSelection;

      // Compute plan bounds to include rooms, walls and furniture
      Rectangle2D homeBounds = null;
      boolean containsVisibleWalls = false;
      for (Wall wall : selectionEmpty
                           ? home.getWalls()
                           : Home.getWallsSubList(selectedItems)) {
        if (isItemAtVisibleLevel(wall)) {
          containsVisibleWalls = true;
          for (float [] point : wall.getPoints()) {
            homeBounds = updateHomeBounds(homeBounds, point [0], point [1]);
          }
        }
      }
      for (HomePieceOfFurniture piece : selectionEmpty 
                                            ? home.getFurniture()
                                            : Home.getFurnitureSubList(selectedItems)) {
        if (piece.isVisible() && isItemAtVisibleLevel(piece)) {
          for (float [] point : piece.getPoints()) {
            homeBounds = updateHomeBounds(homeBounds, point [0], point [1]);
          }
        }
      }
      for (Room room : selectionEmpty 
                           ? home.getRooms()
                           : Home.getRoomsSubList(selectedItems)) {
        if (isItemAtVisibleLevel(room)) {
          for (float [] point : room.getPoints()) {
            homeBounds = updateHomeBounds(homeBounds, point [0], point [1]);
          }
        }
      }

      if (homeBounds != null) {
        if (!containsVisibleWalls || !selectionEmpty) {
          // If home contains only rooms and furniture don't fix minimum size
          return homeBounds;
        } else {
          // Ensure plan bounds are always minimum 10 meters wide centered in middle of 3D view
          return new Rectangle2D.Float(
              (float)(MIN_BOUNDS.getWidth() < homeBounds.getWidth() 
                          ? homeBounds.getMinX()
                          : homeBounds.getCenterX() - MIN_BOUNDS.getWidth() / 2), 
              (float)(MIN_BOUNDS.getHeight() < homeBounds.getHeight() 
                          ? homeBounds.getMinY()
                          : homeBounds.getCenterY() - MIN_BOUNDS.getHeight() / 2), 
              (float)Math.max(MIN_BOUNDS.getWidth(), homeBounds.getWidth()), 
              (float)Math.max(MIN_BOUNDS.getHeight(), homeBounds.getHeight()));
        }
      } else {
        return MIN_BOUNDS;
      }
    }

    /**
     * Returns <code>true</code> if the given <code>item</code> is at a visible level.
     */
    private boolean isItemAtVisibleLevel(Elevatable item) {
      return item.getLevel() == null || item.getLevel().isVisible();
    }
    
    /**
     * Adds the point at the given coordinates to <code>homeBounds</code>.
     */
    private Rectangle2D updateHomeBounds(Rectangle2D homeBounds,
                                         float x, float y) {
      if (homeBounds == null) {
        homeBounds = new Rectangle2D.Float(x, y, 0, 0);
      } else {
        homeBounds.add(x, y);
      }
      return homeBounds;
    }

    /**
     * Returns the minimum distance of the camera to home center.
     */
    private float getMinDistanceToHomeCenter(Rectangle2D homeBounds,
                                             boolean centerOnSelection) {
      List<Selectable> selectedItems = home.getSelectedItems();
      boolean selectionEmpty = selectedItems.size() == 0 || !centerOnSelection;
      
      float maxHeight = 0;
      Collection<Wall> walls = selectionEmpty 
          ? home.getWalls() 
          : Home.getWallsSubList(selectedItems);
      if (walls.isEmpty()) {
        // If home contains no wall, search the max height of the highest piece
        for (HomePieceOfFurniture piece : selectionEmpty 
                                              ? home.getFurniture()
                                              : Home.getFurnitureSubList(selectedItems)) {
          if (piece.isVisible()) {
            maxHeight = Math.max(maxHeight, piece.getGroundElevation() + piece.getHeight());
          }
        }
      } else {
         // Search the max height of the highest wall
        for (Wall wall : walls) {
          Float height = wall.getHeight();
          Level wallLevel = wall.getLevel();
          float levelElevation = wallLevel == null 
              ? 0
              : wallLevel.getElevation(); 
          if (height != null) {
            maxHeight = Math.max(maxHeight, levelElevation + height);
          }
          Float heightAtEnd = wall.getHeightAtEnd();
          if (heightAtEnd != null) {
            maxHeight = Math.max(maxHeight, levelElevation + heightAtEnd);
          }
        }
      }
      if (maxHeight > 0) {
        maxHeight = Math.max(10, maxHeight);
      } else {
        maxHeight = home.getWallHeight();        
      }
      double halfDiagonal = Math.sqrt(homeBounds.getWidth() * homeBounds.getWidth() + homeBounds.getHeight() * homeBounds.getHeight()) / 2;
      return (float)Math.sqrt(maxHeight * maxHeight + halfDiagonal * halfDiagonal) * 1.1f;
    }
    
    /**
     * Returns the maximum distance of the camera to home center.
     */
    private float getMaxDistanceToHomeCenter(Rectangle2D homeBounds, float minDistanceToHomeCenter) {
      List<Selectable> selectedItems = home.getSelectedItems();
      boolean selectionEmpty = selectedItems.size() == 0 || !this.aerialViewCenteredOnSelectionEnabled;
      if (selectionEmpty) {
        return 5 * minDistanceToHomeCenter;
      } else {
        return 5 * getMinDistanceToHomeCenter(homeBounds, false);
      }
    }
    
    @Override
    public void moveCamera(float delta) {
      // Use a 5 times bigger delta for top camera move
      delta *= 5;
      float newDistanceToCenter = (float)Math.sqrt(Math.pow(this.homeBounds.getCenterX() - this.topCamera.getX(), 2) 
          + Math.pow(this.homeBounds.getCenterY() - this.topCamera.getY(), 2) 
          + Math.pow(this.topCamera.getZ(), 2)) - delta;
      placeCameraAt(newDistanceToCenter);
    }

    public void placeCameraAt(float distanceToCenter) {
      // Check camera is always outside the sphere centered in home center and with a radius equal to minimum distance   
      distanceToCenter = Math.max(distanceToCenter, this.minDistanceToHomeCenter);
      // Check camera isn't too far
      distanceToCenter = Math.min(distanceToCenter, this.maxDistanceToHomeCenter);
      double distanceToCenterAtGroundLevel = distanceToCenter * Math.cos(this.topCamera.getPitch());
      this.topCamera.setX((float)this.homeBounds.getCenterX() + (float)(Math.sin(this.topCamera.getYaw()) 
          * distanceToCenterAtGroundLevel));
      this.topCamera.setY((float)this.homeBounds.getCenterY() - (float)(Math.cos(this.topCamera.getYaw()) 
          * distanceToCenterAtGroundLevel));
      this.topCamera.setZ((float)Math.sin(this.topCamera.getPitch()) * distanceToCenter);
    }

    @Override
    public void rotateCameraYaw(float delta) {
      float  newYaw = this.topCamera.getYaw() + delta;
      double distanceToCenterAtGroundLevel = this.topCamera.getZ() / Math.tan(this.topCamera.getPitch());
      // Change camera yaw and location so user turns around home
      this.topCamera.setYaw(newYaw); 
      this.topCamera.setX((float)this.homeBounds.getCenterX() + (float)(Math.sin(newYaw) * distanceToCenterAtGroundLevel));
      this.topCamera.setY((float)this.homeBounds.getCenterY() - (float)(Math.cos(newYaw) * distanceToCenterAtGroundLevel));
    }
    
    @Override
    public void rotateCameraPitch(float delta) {
      float newPitch = this.topCamera.getPitch() + delta;
      // Check new pitch is between PI / 2 and PI / 16  
      newPitch = Math.max(newPitch, (float)Math.PI / 16);
      newPitch = Math.min(newPitch, (float)Math.PI / 2);
      // Compute new z to keep the same distance to view center
      double cameraToBoundsCenterDistance = Math.sqrt(Math.pow(this.topCamera.getX() - this.homeBounds.getCenterX(), 2)
          + Math.pow(this.topCamera.getY() - this.homeBounds.getCenterY(), 2)
          + Math.pow(this.topCamera.getZ(), 2));
      float newZ = (float)(cameraToBoundsCenterDistance * Math.sin(newPitch));
      double distanceToCenterAtGroundLevel = newZ / Math.tan(newPitch);
      // Change camera pitch 
      this.topCamera.setPitch(newPitch); 
      this.topCamera.setX((float)this.homeBounds.getCenterX() + (float)(Math.sin(this.topCamera.getYaw()) 
          * distanceToCenterAtGroundLevel));
      this.topCamera.setY((float)this.homeBounds.getCenterY() - (float)(Math.cos(this.topCamera.getYaw()) 
          * distanceToCenterAtGroundLevel));
      this.topCamera.setZ(newZ);
    }
    
    @Override
    public void goToCamera(Camera camera) {
      this.topCamera.setCamera(camera);
      this.topCamera.setTime(camera.getTime());
      this.topCamera.setLens(camera.getLens());
      updateCameraFromHomeBounds();
    }
    
    @Override
    public void exit() {
      this.topCamera = null;
      for (Wall wall : home.getWalls()) {
        wall.removePropertyChangeListener(this.objectChangeListener);
      }
      home.removeWallsListener(wallsListener);
      for (HomePieceOfFurniture piece : home.getFurniture()) {
        piece.removePropertyChangeListener(this.objectChangeListener);
      }
      home.removeFurnitureListener(this.furnitureListener);
      for (Room room : home.getRooms()) {
        room.removePropertyChangeListener(this.objectChangeListener);
      }
      home.removeRoomsListener(this.roomsListener);
      for (Level room : home.getLevels()) {
        room.removePropertyChangeListener(this.levelVisibilityChangeListener);
      }
      home.removeLevelsListener(this.levelsListener);
      home.removeSelectionListener(this.selectionListener);
    }
  }
  
  /**
   * Preferences property listener bound to top camera state with a weak reference to avoid
   * strong link between user preferences and top camera state.  
   */
  private static class UserPreferencesChangeListener implements PropertyChangeListener {
    private WeakReference<TopCameraState>  topCameraState;

    public UserPreferencesChangeListener(TopCameraState topCameraState) {
      this.topCameraState = new WeakReference<TopCameraState>(topCameraState);
    }
    
    public void propertyChange(PropertyChangeEvent ev) {
      // If top camera state was garbage collected, remove this listener from preferences
      TopCameraState topCameraState = this.topCameraState.get();
      UserPreferences preferences = (UserPreferences)ev.getSource();
      if (topCameraState == null) {
        preferences.removePropertyChangeListener(UserPreferences.Property.valueOf(ev.getPropertyName()), this);
      } else {
        topCameraState.setAerialViewCenteredOnSelectionEnabled(preferences.isAerialViewCenteredOnSelectionEnabled());
      }
    }
  }

  /**
   * Observer camera controller state. 
   */
  private class ObserverCameraState extends CameraControllerState {
    private ObserverCamera observerCamera;
    private PropertyChangeListener levelElevationChangeListener = new PropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent ev) {
          if (Level.Property.ELEVATION.name().equals(ev.getPropertyName())) {
            updateCameraMinimumElevation();
          }
        }
      };
    private CollectionListener<Level> levelsListener = new CollectionListener<Level>() {
        public void collectionChanged(CollectionEvent<Level> ev) {
          if (ev.getType() == CollectionEvent.Type.ADD) {
            ev.getItem().addPropertyChangeListener(levelElevationChangeListener);
          } else if (ev.getType() == CollectionEvent.Type.DELETE) {
            ev.getItem().removePropertyChangeListener(levelElevationChangeListener);
          } 
          updateCameraMinimumElevation();
        }
      };

    @Override
    public void enter() {
      this.observerCamera = (ObserverCamera)home.getCamera();
      for (Level level : home.getLevels()) {
        level.addPropertyChangeListener(this.levelElevationChangeListener);
      }
      home.addLevelsListener(this.levelsListener);
      // Select observer camera for user feedback
      home.setSelectedItems(Arrays.asList(new Selectable [] {this.observerCamera}));
    }
    
    @Override
    public void moveCamera(float delta) {
      this.observerCamera.setX(this.observerCamera.getX() - (float)Math.sin(this.observerCamera.getYaw()) * delta);
      this.observerCamera.setY(this.observerCamera.getY() + (float)Math.cos(this.observerCamera.getYaw()) * delta);
      // Select observer camera for user feedback
      home.setSelectedItems(Arrays.asList(new Selectable [] {this.observerCamera}));
    }
    
    @Override
    public void elevateCamera(float delta) {
      float newElevation = this.observerCamera.getZ() + delta; 
      newElevation = Math.min(Math.max(newElevation, getMinimumElevation()), preferences.getLengthUnit().getMaximumElevation());
      this.observerCamera.setZ(newElevation);
      // Select observer camera for user feedback
      home.setSelectedItems(Arrays.asList(new Selectable [] {this.observerCamera}));
    }

    private void updateCameraMinimumElevation() {
      observerCamera.setZ(Math.max(observerCamera.getZ(), getMinimumElevation()));
    }

    public float getMinimumElevation() {
      List<Level> levels = home.getLevels();
      if (levels.size() > 0) {
        return 10 + levels.get(0).getElevation();
      } else {
        return 10;
      }
    }
    
    @Override
    public void rotateCameraYaw(float delta) {
      this.observerCamera.setYaw(this.observerCamera.getYaw() + delta); 
      // Select observer camera for user feedback
      home.setSelectedItems(Arrays.asList(new Selectable [] {this.observerCamera}));
    }
    
    @Override
    public void rotateCameraPitch(float delta) {
      float newPitch = this.observerCamera.getPitch() + delta; 
      // Check new angle is between -60� and 75�  
      newPitch = Math.max(newPitch, -(float)Math.PI / 3);
      newPitch = Math.min(newPitch, (float)Math.PI / 36 * 15);
      this.observerCamera.setPitch(newPitch); 
      // Select observer camera for user feedback
      home.setSelectedItems(Arrays.asList(new Selectable [] {this.observerCamera}));
    }
    
    @Override
    public void goToCamera(Camera camera) {
      this.observerCamera.setCamera(camera);
      this.observerCamera.setTime(camera.getTime());
      this.observerCamera.setLens(camera.getLens());
    }
    
    @Override
    public void exit() {
      // Remove observer camera from selection
      List<Selectable> selectedItems = home.getSelectedItems();
      if (selectedItems.contains(this.observerCamera)) {
        selectedItems = new ArrayList<Selectable>(selectedItems);
        selectedItems.remove(this.observerCamera);
        home.setSelectedItems(selectedItems);
      }
      for (Level room : home.getLevels()) {
        room.removePropertyChangeListener(this.levelElevationChangeListener);
      }
      home.removeLevelsListener(this.levelsListener);
      this.observerCamera = null;
    }
  }
}
