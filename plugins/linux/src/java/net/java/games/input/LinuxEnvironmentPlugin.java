/**
 * Copyright (C) 2003 Jeremy Booth (jeremy@newdawnsoftware.com)
 *
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this 
 * list of conditions and the following disclaimer. Redistributions in binary 
 * form must reproduce the above copyright notice, this list of conditions and 
 * the following disclaimer in the documentation and/or other materials provided
 * with the distribution. 
 * The name of the author may not be used to endorse or promote products derived
 * from this software without specific prior written permission. 
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF 
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO 
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, 
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; 
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR 
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF 
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE
 */
package net.java.games.input;

// === java imports === //
import java.util.*;
import java.io.IOException;
import java.io.File;
import java.io.FilenameFilter;
import java.security.AccessController;
import java.security.PrivilegedAction;
// === jinput imports === //
import net.java.games.util.plugins.Plugin;

/** Environment plugin for linux
 * @author elias
 * @author Jeremy Booth (jeremy@newdawnsoftware.com)
 */
public final class LinuxEnvironmentPlugin extends ControllerEnvironment implements Plugin {

	// ============= Class variables ============== //
	private final static String LIBNAME = "jinput-linux";
	private final static String POSTFIX64BIT = "64";
	private final static LinuxDeviceThread device_thread = new LinuxDeviceThread();
	private final List<Controller> controllers;
	private final List<LinuxDevice> devices;
	private final HashMap<LinuxDevice, Controller> controllerDeviceMap;
	private static boolean supported;

	// ============= Constructors ============== //
	public LinuxEnvironmentPlugin() {
		controllers = new ArrayList<>();
		devices = new ArrayList<>();
		controllerDeviceMap = new HashMap<>();
		Controller[] arrayControllers = scanControllers();
		Collections.addAll(controllers, arrayControllers);
	}

	// ============= Public Methods ============== //
	/**
	 * Returns a list of all controllers available to this environment,
	 * or an empty array if there are no controllers in this environment.
	 *
	 * @return Returns a list of all controllers available to this environment,
	 * or an empty array if there are no controllers in this environment.
	 */
	public final Controller[] getControllers() {
		Controller[] ret = new Controller[controllers.size()];
		for (int i = 0; i < controllers.size(); i++) ret[i] = controllers.get(i);
		return ret;
	}
	@Override
	public Controller[] rescanControllers() {
		Controller[] controllerArray;
		if (isSupported()) {
			List<Controller> eventControllers = new ArrayList<>();
			List<Controller> jsControllers = new ArrayList<>();
			for (Controller controller : controllers) {
				if (controller.getType() == Controller.Type.MOUSE ||
						controller.getType() == Controller.Type.KEYBOARD)  {
					eventControllers.add(controller);
				} else if (controller.getType() == Controller.Type.STICK ||
						controller.getType() == Controller.Type.FINGERSTICK ||
						controller.getType() == Controller.Type.GAMEPAD) {
					jsControllers.add(controller);
				} else System.out.println("Unhandled controller type: "+controller.getType());
			}
			rescanEventControllers(eventControllers);
			rescanJoystickControllers(jsControllers);
			controllerArray = enumerateControllers(eventControllers, jsControllers);
		} else controllerArray = new Controller[0];
		controllers.clear();
		Collections.addAll(controllers, controllerArray);
		return controllerArray;
	}
	public boolean isSupported() { return supported; }

	// ============= Private Methods ============== //
	private Controller[] scanControllers() {
		Controller[] controllerArray;
		if (isSupported()) {
			controllerArray = enumerateControllers();
			AccessController.doPrivileged((PrivilegedAction) () -> {
				Runtime.getRuntime().addShutdownHook(new ShutdownHook());
				return null;
			});
		} else controllerArray = new Controller[0];
		return controllerArray;
	}
	private Controller[] enumerateControllers() {
		List<Controller> eventControllers = new ArrayList<>();
		List<Controller> jsControllers = new ArrayList<>();
		enumerateEventControllers(eventControllers);
		enumerateJoystickControllers(jsControllers);
		return enumerateControllers(eventControllers, jsControllers);
	}
	private Controller[] enumerateControllers(List<Controller> eventControllers, List<Controller> jsControllers) {
		List<Controller> localControllers = new ArrayList<>();
		for (int i = 0; i < eventControllers.size(); i++) {
			for (int j = 0; j < jsControllers.size(); j++) {
				Controller evController = eventControllers.get(i);
				Controller jsController = jsControllers.get(j);
				// compare
				// Check if the nodes have the same name
				if (evController.getName().equals(jsController.getName()) &&
				evController.getType() != jsController.getType()) {
					// Check they have the same component count
					Component[] evComponents = evController.getComponents();
					Component[] jsComponents = jsController.getComponents();
					if (evComponents.length == jsComponents.length) {
						boolean foundADifference = false;
						// check the component pairs are of the same type
						for (int k = 0; k < evComponents.length; k++) {
							// Check the type of the component is the same
							if (!(evComponents[k].getIdentifier() == jsComponents[k].getIdentifier()))
								foundADifference = true;
						}
						if (!foundADifference) {
							LinuxCombinedController combinedController = new LinuxCombinedController((LinuxAbstractController) eventControllers.remove(i), (LinuxJoystickAbstractController) jsControllers.remove(j));
							localControllers.add(combinedController);
							for (LinuxDevice device : devices) {
								Controller deviceController = controllerDeviceMap.get(device);
								if (deviceController == evController || deviceController == jsController)
									controllerDeviceMap.put(device,combinedController);
							}
							i--;
							j--;
							break;
						}
					}
				}
			}
		}
		localControllers.addAll(eventControllers);
		localControllers.addAll(jsControllers);
		Controller[] controllers_array = new Controller[localControllers.size()];
		localControllers.toArray(controllers_array);
		return controllers_array;
	}
	private void enumerateEventControllers(List<Controller> controllers) {
		final File dev = new File("/dev/input");
		File[] event_device_files = listFilesPrivileged(dev, (dir, name) -> name.startsWith("event"));
		if (event_device_files == null)	return;
		for (File event_file : event_device_files) {
			if (!event_file.canRead()) {
				logln("Insufficient privileges: Failed to read device " + event_file.getPath());
				continue;
			}
			try {
				String path = getAbsolutePathPrivileged(event_file);
				LinuxEventDevice device = new LinuxEventDevice(path);
				devices.add(device);
				try {
					Controller controller = createControllerFromDevice(device);
					if (controller != null) {
						controllers.add(controller);
						controllerDeviceMap.put(device, controller);
					} else device.close();
				} catch (IOException e) {
					logln("Failed to create Controller: " + e.getMessage());
					device.close();
				}
			} catch (IOException e) {
				logln("Insufficient privileges: " + e.getMessage());
			}
		}
	}
	private void enumerateJoystickControllers(List<Controller> controllers) {
		File[] joystick_device_files = enumerateJoystickDeviceFiles("/dev/input");
		if (joystick_device_files == null || joystick_device_files.length == 0) {
			joystick_device_files = enumerateJoystickDeviceFiles("/dev");
			if (joystick_device_files == null) return;
		}
		for (File event_file : joystick_device_files) {
			if (!event_file.canRead()) {
				logln("Insufficient privileges: Failed to read device " + event_file.getPath());
				continue;
			}
			try {
				String path = getAbsolutePathPrivileged(event_file);
				LinuxJoystickDevice device = new LinuxJoystickDevice(path);
				devices.add(device);
				Controller controller = createJoystickFromJoystickDevice(device);
				controllers.add(controller);
				controllerDeviceMap.put(device, controller);
			} catch (IOException e) {
				logln(e.getMessage());
			}
		}
	}
	private void rescanEventControllers(List<Controller> controllers) {
		final File dev = new File("/dev/input");
		File[] event_device_files = listFilesPrivileged(dev, (dir, name) -> name.startsWith("event"));
		if (event_device_files == null) return;
		// check for new events
		for (File event_file : event_device_files) {
			String path = getAbsolutePathPrivileged(event_file);
			if (!event_file.canRead()) continue; //Insufficient privileges
			try {
				LinuxEventDevice device = null;
				for (LinuxDevice knownDevice : devices) {
					if (knownDevice.getFilename().equals(event_file.getName())) {
						device = (LinuxEventDevice) knownDevice;
						break;
					}
				}
				if (device == null) {
					device = new LinuxEventDevice(path);
					devices.add(device);
					try {
						boolean isNewEvent = true;
						Controller controller = createControllerFromDevice(device);
						if (controller != null) {
							for (Controller knownControllers : controllerDeviceMap.values()) {
								if (knownControllers.getName().equals(controller.getName()) &&
										knownControllers.getType() == controller.getType())
									isNewEvent = false;
							}
							if (isNewEvent) {
								controllers.add(controller);
								controllerDeviceMap.put(device, controller);
							}
						} else device.close();
					} catch (IOException e) {
						logln("Failed to create Controller: " + e.getMessage());
						device.close();
					}
				}
			} catch (IOException e) {
				logln(e.getMessage());
			}
		}
		// now check for a device that previous was connected and since has disconnected
		ArrayList<LinuxDevice> removeDevices = new ArrayList<>();
		for (LinuxDevice testDevice : devices) {
			if (testDevice instanceof LinuxEventDevice) {
				boolean fileExists = false;
				for (File event_file : event_device_files) {
					String testDeviceFilename;
					testDeviceFilename = testDevice.getFilename();
					if (testDeviceFilename.equals(event_file.getName())) {
						fileExists = true;
						break;
					}
				}
				if (!fileExists) removeDevices.add(testDevice);
			} else continue;
		}
		for (LinuxDevice testDevice : removeDevices) removeDevice(testDevice, controllers);
	}
	private void rescanJoystickControllers(List<Controller> controllers) {
		File[] joystick_device_files = enumerateJoystickDeviceFiles("/dev/input");
		if (joystick_device_files == null || joystick_device_files.length == 0) {
			joystick_device_files = enumerateJoystickDeviceFiles("/dev");
			if (joystick_device_files == null) return;
		}
		for (File event_file : joystick_device_files) {
			if (!event_file.canRead()) continue; //Insufficient privileges
			LinuxJoystickDevice device = null;
			for (LinuxDevice knownDevice : devices) {
				if (knownDevice.getFilename().equals(event_file.getAbsoluteFile().toString())) {
					device = (LinuxJoystickDevice) knownDevice;
					break;
				}
			}
			if (device == null) {
				try {
					String path = getAbsolutePathPrivileged(event_file);
					device = new LinuxJoystickDevice(path);
					devices.add(device);
					boolean isNewEvent = true;
					Controller controller = createJoystickFromJoystickDevice(device);
					if (controller != null) {
						for (Controller knownControllers : controllerDeviceMap.values()) {
							if (knownControllers.getName().equals(controller.getName()) &&
									knownControllers.getType() == controller.getType())
								isNewEvent = false;
						}
						if (isNewEvent) {
							controllers.add(controller);
							controllerDeviceMap.put(device, controller);
						}
					} else device.close();
				} catch (IOException e) {
					logln(e.getMessage());
				}
			}
		}
		// now check for a device that previous was connected and since has disconnected
		ArrayList<LinuxDevice> removeDevices = new ArrayList<>();
		for (LinuxDevice testDevice : devices) {
			if (testDevice instanceof LinuxJoystickDevice) {
				boolean fileExists = false;
				for (File event_file : joystick_device_files) {
					String testDeviceFilename = testDevice.getFilename();
					if (testDeviceFilename.equals(event_file.getAbsoluteFile().toString())) {
						fileExists = true;
						break;
					}
				}
				if (!fileExists) removeDevices.add(testDevice);
			} else continue;
		}
		for (LinuxDevice testDevice : removeDevices) removeDevice(testDevice, controllers);
	}
	private void removeDevice(LinuxDevice device, List<Controller> controllers) {
		devices.remove(device);
		try { device.close(); }
		catch (IOException ex) { }
		Controller controller = controllerDeviceMap.get(device);
		if (controller != null) {
			controllers.remove(controller);
			this.controllers.remove(controller);
			controllerDeviceMap.remove(device);
		}
	}

	// ============= Static Methods ============== //
	/**
	 * Static utility method for loading native libraries.
	 * It will try to load from either the path given by
	 * the net.java.games.input.librarypath property
	 * or through System.loadLibrary().
	 */
	private static void loadLibrary(final String lib_name) {
		AccessController.doPrivileged(
				(PrivilegedAction) () -> {
					String lib_path = System.getProperty("net.java.games.input.librarypath");
					try {
						if (lib_path != null)
							System.load(lib_path + File.separator + System.mapLibraryName(lib_name));
						else
							System.loadLibrary(lib_name);
					} catch (UnsatisfiedLinkError e) {
						logln("Failed to load library: " + e.getMessage());
						e.printStackTrace();
						supported = false;
					}
					return null;
				}
		);
	}
	private static String getPrivilegedProperty(final String property) {
		return (String) AccessController.doPrivileged((PrivilegedAction) () -> System.getProperty(property));
	}
	private static String getPrivilegedProperty(final String property, final String default_value) {
		return (String) AccessController.doPrivileged((PrivilegedAction) () -> System.getProperty(property, default_value));
	}
	static {
		String osName = getPrivilegedProperty("os.name", "").trim();
		if (osName.equals("Linux")) {
			supported = true;
			if ("i386".equals(getPrivilegedProperty("os.arch"))) loadLibrary(LIBNAME);
			else loadLibrary(LIBNAME + POSTFIX64BIT);
		}
	}
	public static Object execute(LinuxDeviceTask task) throws IOException {
		return device_thread.execute(task);
	}
	private static Component[] createComponents(List event_components, LinuxEventDevice device) {
		LinuxEventComponent[][] povs = new LinuxEventComponent[4][2];
		List<Component> components = new ArrayList<>();
		for (Object eventComponent : event_components) {
			LinuxEventComponent event_component = (LinuxEventComponent) eventComponent;
			Component.Identifier identifier = event_component.getIdentifier();
			if (identifier == Component.Identifier.Axis.POV) {
				int native_code = event_component.getDescriptor().getCode();
				switch (native_code) {
					case NativeDefinitions.ABS_HAT0X -> povs[0][0] = event_component;
					case NativeDefinitions.ABS_HAT0Y -> povs[0][1] = event_component;
					case NativeDefinitions.ABS_HAT1X -> povs[1][0] = event_component;
					case NativeDefinitions.ABS_HAT1Y -> povs[1][1] = event_component;
					case NativeDefinitions.ABS_HAT2X -> povs[2][0] = event_component;
					case NativeDefinitions.ABS_HAT2Y -> povs[2][1] = event_component;
					case NativeDefinitions.ABS_HAT3X -> povs[3][0] = event_component;
					case NativeDefinitions.ABS_HAT3Y -> povs[3][1] = event_component;
					default -> logln("Unknown POV instance: " + native_code);
				}
			} else if (identifier != null) {
				LinuxComponent component = new LinuxComponent(event_component);
				components.add(component);
				device.registerComponent(event_component.getDescriptor(), component);
			}
		}
		for (LinuxEventComponent[] pov : povs) {
			LinuxEventComponent x = pov[0];
			LinuxEventComponent y = pov[1];
			if (x != null && y != null) {
				LinuxComponent controller_component = new LinuxPOV(x, y);
				components.add(controller_component);
				device.registerComponent(x.getDescriptor(), controller_component);
				device.registerComponent(y.getDescriptor(), controller_component);
			}
		}
		Component[] components_array = new Component[components.size()];
		components.toArray(components_array);
		return components_array;
	}
	private static Mouse createMouseFromDevice(LinuxEventDevice device, Component[] components) throws IOException {
		Mouse mouse = new LinuxMouse(device, components, new Controller[]{}, device.getRumblers());
		if (mouse.getX() != null && mouse.getY() != null && mouse.getPrimaryButton() != null)
			return mouse;
		else return null;
	}
	private static Keyboard createKeyboardFromDevice(LinuxEventDevice device, Component[] components) throws IOException {
		return new LinuxKeyboard(device, components, new Controller[]{}, device.getRumblers());
	}
	private static Controller createJoystickFromDevice(LinuxEventDevice device, Component[] components, Controller.Type type) throws IOException {
		return new LinuxAbstractController(device, components, new Controller[]{}, device.getRumblers(), type);
	}
	private static Controller createControllerFromDevice(LinuxEventDevice device) throws IOException {
		List event_components = device.getComponents();
		Component[] components = createComponents(event_components, device);
		Controller.Type type = device.getType();
		if (type == Controller.Type.MOUSE) {
			return createMouseFromDevice(device, components);
		} else if (type == Controller.Type.KEYBOARD) {
			return createKeyboardFromDevice(device, components);
		} else if (type == Controller.Type.STICK || type == Controller.Type.GAMEPAD) {
			return createJoystickFromDevice(device, components, type);
		} else return null;
	}
	private static Controller createJoystickFromJoystickDevice(LinuxJoystickDevice device) {
		List<Component> components = new ArrayList<>();
		byte[] axisMap = device.getAxisMap();
		char[] buttonMap = device.getButtonMap();
		LinuxJoystickAxis[] hatBits = new LinuxJoystickAxis[6];
		for (int i = 0; i < device.getNumButtons(); i++) {
			Component.Identifier button_id = LinuxNativeTypesMap.getButtonID(buttonMap[i]);
			if (button_id != null) {
				LinuxJoystickButton button = new LinuxJoystickButton(button_id);
				device.registerButton(i, button);
				components.add(button);
			}
		}
		for (int i = 0; i < device.getNumAxes(); i++) {
			Component.Identifier.Axis axis_id;
			axis_id = (Component.Identifier.Axis) LinuxNativeTypesMap.getAbsAxisID(axisMap[i]);
			LinuxJoystickAxis axis = new LinuxJoystickAxis(axis_id);
			device.registerAxis(i, axis);
			if (axisMap[i] == NativeDefinitions.ABS_HAT0X) {
				hatBits[0] = axis;
			} else if (axisMap[i] == NativeDefinitions.ABS_HAT0Y) {
				hatBits[1] = axis;
				axis = new LinuxJoystickPOV(Component.Identifier.Axis.POV, hatBits[0], hatBits[1]);
				device.registerPOV((LinuxJoystickPOV) axis);
				components.add(axis);
			} else if (axisMap[i] == NativeDefinitions.ABS_HAT1X) {
				hatBits[2] = axis;
			} else if (axisMap[i] == NativeDefinitions.ABS_HAT1Y) {
				hatBits[3] = axis;
				axis = new LinuxJoystickPOV(Component.Identifier.Axis.POV, hatBits[2], hatBits[3]);
				device.registerPOV((LinuxJoystickPOV) axis);
				components.add(axis);
			} else if (axisMap[i] == NativeDefinitions.ABS_HAT2X) {
				hatBits[4] = axis;
			} else if (axisMap[i] == NativeDefinitions.ABS_HAT2Y) {
				hatBits[5] = axis;
				axis = new LinuxJoystickPOV(Component.Identifier.Axis.POV, hatBits[4], hatBits[5]);
				device.registerPOV((LinuxJoystickPOV) axis);
				components.add(axis);
			} else components.add(axis);
		}
		return new LinuxJoystickAbstractController(device, components.toArray(new Component[]{}), new Controller[]{}, new Rumbler[]{});
	}
	private static File[] enumerateJoystickDeviceFiles(final String dev_path) {
		final File dev = new File(dev_path);
		return listFilesPrivileged(dev, (dir, name) -> name.startsWith("js"));
	}
	private static String getAbsolutePathPrivileged(final File file) {
		return (String) AccessController.doPrivileged((PrivilegedAction) file::getAbsolutePath);
	}
	private static File[] listFilesPrivileged(final File dir, final FilenameFilter filter) {
		return (File[]) AccessController.doPrivileged((PrivilegedAction) () -> {
			File[] files = dir.listFiles(filter);
			if (files != null) Arrays.sort(files, (Comparator) (f1, f2) -> ((File) f1).getName().compareTo(((File) f2).getName()));
			return files;
		});
	}
	private final class ShutdownHook extends Thread {
		public final void run() {
			for (LinuxDevice linuxDevice : devices) {
				try { linuxDevice.close(); }
				catch (IOException e) {	logln( e.getMessage()); }
			}
		}
	}
	private static Component.Identifier.Button getButtonIdentifier(int index) {
		return switch (index) {
			case 0 -> Component.Identifier.Button._0;
			case 1 -> Component.Identifier.Button._1;
			case 2 -> Component.Identifier.Button._2;
			case 3 -> Component.Identifier.Button._3;
			case 4 -> Component.Identifier.Button._4;
			case 5 -> Component.Identifier.Button._5;
			case 6 -> Component.Identifier.Button._6;
			case 7 -> Component.Identifier.Button._7;
			case 8 -> Component.Identifier.Button._8;
			case 9 -> Component.Identifier.Button._9;
			case 10 -> Component.Identifier.Button._10;
			case 11 -> Component.Identifier.Button._11;
			case 12 -> Component.Identifier.Button._12;
			case 13 -> Component.Identifier.Button._13;
			case 14 -> Component.Identifier.Button._14;
			case 15 -> Component.Identifier.Button._15;
			case 16 -> Component.Identifier.Button._16;
			case 17 -> Component.Identifier.Button._17;
			case 18 -> Component.Identifier.Button._18;
			case 19 -> Component.Identifier.Button._19;
			case 20 -> Component.Identifier.Button._20;
			case 21 -> Component.Identifier.Button._21;
			case 22 -> Component.Identifier.Button._22;
			case 23 -> Component.Identifier.Button._23;
			case 24 -> Component.Identifier.Button._24;
			case 25 -> Component.Identifier.Button._25;
			case 26 -> Component.Identifier.Button._26;
			case 27 -> Component.Identifier.Button._27;
			case 28 -> Component.Identifier.Button._28;
			case 29 -> Component.Identifier.Button._29;
			case 30 -> Component.Identifier.Button._30;
			case 31 -> Component.Identifier.Button._31;
			default -> null;
		};
	}
}
