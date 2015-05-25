/**
 * Author : Rishi Gupta
 * 
 * This file is part of 'serial communication manager' library.
 *
 * The 'serial communication manager' is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by the Free Software 
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * The 'serial communication manager' is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with serial communication manager. If not, see <http://www.gnu.org/licenses/>.
 */
package com.embeddedunveiled.serial;

import java.io.IOException;
import java.io.InputStream;

/**
 * <p>This class represents an input stream of bytes which is received from serial port.</p>
 */
public final class SerialComInByteStream extends InputStream {

	private SerialComManager scm = null;
	private long handle = 0;
	private boolean isOpened = false;

	public SerialComInByteStream(SerialComManager scm, long handle) {
		this.scm = scm;
		this.handle = handle;
		isOpened = true;
	}

	/**
	 * <p>Returns an estimate of the minimum number of bytes that can be read from this input stream
	 * without blocking by the next invocation of a method for this input stream.</p>
	 * @return an estimate of the minimum number of bytes that can be read from this input stream without blocking
	 * @throws IOException - if an I/O error occurs.
	 */
	@Override
	public int available() throws IOException {
		if(isOpened != true) {
			throw new IOException(SerialComErrorMapper.ERR_BYTE_STREAM_IS_CLOSED);
		}
		
		int[] numBytesAvailable = new int[2];
		try {
			numBytesAvailable = scm.getByteCountInPortIOBuffer(handle);
		} catch (SerialComException e) {
			throw new IOException(e);
		}
		return numBytesAvailable[0];
	}

	/**
	 * <p>scm does not support mark and reset of input stream. If required, it can be developed at application level.</p>
	 * @return always returns false
	 */
	@Override
	public boolean markSupported() {
		return false;
	}

	/**
	 * <p>Reads the next byte of data from the input stream. The value byte is returned as an int in 
	 * the range 0 to 255. If no byte is available because the end of the stream has been reached, 
	 * the value -1 is returned.</p>
	 * 
	 * <p>From the perspective of serial port communication, it should be noted that when this method 
	 * returns 0 which indicate that there was no data, data might have arrived just after the instant
	 * this method returned.</p>
	 * 
	 * @return the next byte of data, or 0 no byte is read.
	 * @throws IOException - if an I/O error occurs.
	 */
	@Override
	public int read() throws IOException {
		if(isOpened != true) {
			throw new IOException(SerialComErrorMapper.ERR_BYTE_STREAM_IS_CLOSED);
		}
		
		int x = 0;
		byte data[] = new byte[1];
		try {
			data = scm.readBytes(handle, 1);
			if(data == null) {
				x = -1;
			}
		} catch (SerialComException e) {
			throw new IOException(e);
		}

		return x;
	}

	/**
	 * <p>The scm does not support skip. If required, it can be developed at application level.</p>
	 * @param number of bytes to skip
	 * @return always returns 0
	 */
	@Override
	public long skip(long n) {
		return 0;
	}
	
	/**
	 * <p>This method releases the InputStream object associated with the operating handle.</p>
	 * <p>To actually close the port closeComPort() method should be used.</p>
	 */
	@Override
	public void close() throws IOException {
		if(isOpened != true) {
			throw new IOException(SerialComErrorMapper.ERR_BYTE_STREAM_IS_CLOSED);
		}
		scm.destroyInputByteStream(this);
		isOpened = false;
	}
}