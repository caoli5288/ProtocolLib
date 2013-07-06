/*
 *  ProtocolLib - Bukkit server library that allows access to the Minecraft protocol.
 *  Copyright (C) 2012 Kristian S. Stangeland
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the 
 *  GNU General Public License as published by the Free Software Foundation; either version 2 of 
 *  the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with this program; 
 *  if not, write to the Free Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 
 *  02111-1307 USA
 */

package com.comphenix.protocol.injector.packet;

import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.util.Map;

import com.comphenix.protocol.error.ErrorReporter;
import com.comphenix.protocol.error.Report;
import com.comphenix.protocol.error.ReportType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.google.common.collect.MapMaker;

import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

class ReadPacketModifier implements MethodInterceptor {
	public static final ReportType REPORT_CANNOT_HANDLE_CLIENT_PACKET = new ReportType("Cannot handle client packet.");
	
	// A cancel marker
	private static final Object CANCEL_MARKER = new Object();
	
	// Common for all packets of the same type
	private ProxyPacketInjector packetInjector;
	private int packetID;
	
	// Report errors
	private ErrorReporter reporter;
	
	// If this is a read packet data method
	private boolean isReadPacketDataMethod;
	
	// Whether or not a packet has been cancelled
	private static Map<Object, Object> override = new MapMaker().weakKeys().makeMap();
	
	public ReadPacketModifier(int packetID, ProxyPacketInjector packetInjector, ErrorReporter reporter, boolean isReadPacketDataMethod) {
		this.packetID = packetID;
		this.packetInjector = packetInjector;
		this.reporter = reporter;
		this.isReadPacketDataMethod = isReadPacketDataMethod;
	}
	
	/**
	 * Remove any packet overrides.
	 * @param packet - the packet to rever
	 */
	public static void removeOverride(Object packet) {
		override.remove(packet);
	}
	
	/**
	 * Retrieve the packet that overrides the methods of the given packet.
	 * @param packet - the given packet.
	 * @return Overriden object.
	 */
	public static Object getOverride(Object packet) {
		return override.get(packet);
	}

	/**
	 * Determine if the given packet has been cancelled before.
	 * @param packet - the packet to check.
	 * @return TRUE if it has been cancelled, FALSE otherwise.
	 */
	public static boolean hasCancelled(Object packet) {
		return getOverride(packet) == CANCEL_MARKER;
	}

	@Override
	public Object intercept(Object thisObj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
		// Atomic retrieval
		Object overridenObject = override.get(thisObj);
		Object returnValue = null;
		
		if (overridenObject != null) {
			// This packet has been cancelled
			if (overridenObject == CANCEL_MARKER) {
				// So, cancel all void methods
				if (method.getReturnType().equals(Void.TYPE))
					return null;
				else // Revert to normal for everything else
					overridenObject = thisObj;
			}
			
			returnValue = proxy.invokeSuper(overridenObject, args);
		} else {
			returnValue = proxy.invokeSuper(thisObj, args);
		}
		
		// Is this a readPacketData method?
		if (isReadPacketDataMethod) {
			try {
				// We need this in order to get the correct player
				DataInputStream input = (DataInputStream) args[0];
	
				// Let the people know
				PacketContainer container = new PacketContainer(packetID, thisObj);
				PacketEvent event = packetInjector.packetRecieved(container, input);
				
				// Handle override
				if (event != null) {
					Object result = event.getPacket().getHandle();
					
					if (event.isCancelled()) {
						override.put(thisObj, CANCEL_MARKER);
					} else if (!objectEquals(thisObj, result)) {
						override.put(thisObj, result);
					}
				}
			} catch (Throwable e) {
				// Minecraft cannot handle this error
				reporter.reportDetailed(this, 
						Report.newBuilder(REPORT_CANNOT_HANDLE_CLIENT_PACKET).callerParam(args[0]).error(e)
				);
			}
		}
		return returnValue;
	}
	
	private boolean objectEquals(Object a, Object b) {
		return System.identityHashCode(a) != System.identityHashCode(b);
	}
}