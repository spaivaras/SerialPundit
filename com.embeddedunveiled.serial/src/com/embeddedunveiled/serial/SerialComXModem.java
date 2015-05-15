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

import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 *TODO JAVADOC
 */
public final class SerialComXModem {

	private static final byte SOH = 0x01; // Start of header character
	private static final byte EOT = 0x04; // End-of-transmission character
	private static final byte ACK = 0x06; // Acknowledge byte character
	private static final byte NAK = 0x15; // Negative-acknowledge character
	private static final byte SUB = 0x1A; // Substitute/CTRL+Z

	private SerialComManager scm = null;
	private long handle = 0;
	private File fileToProcess = null;

	private int blockNumber = -1;
	private byte[] block = new byte[132];       // 132 bytes xmodem block/packet
	private BufferedInputStream inStream = null;
	private BufferedOutputStream outStream = null;
	private boolean noMoreData = false;


	/**
	 * <p>The constructor, joins instance of this class to the instance of scm.</p>
	 * 
	 * @param scm SerialComManager instance associated with this handle
	 * @param handle of the port on which file is to be sent
	 * @param fileToProcess File instance representing file to be sent
	 */
	public SerialComXModem(SerialComManager scm, long handle, File fileToProcess) {
		this.scm = scm;
		this.handle = handle;
		this.fileToProcess = fileToProcess;
	}

	/**
	 * <p>For internal use only.</p>
	 * <p>Represents actions to execute in state machine to implement xmodem protocol for sending files.</p>
	 */
	public boolean sendFileX() throws SecurityException, IOException, SerialComException {

		// Finite state machine
		final int CONNECT = 0;
		final int BEGINSEND = 1;
		final int WAITACK = 2;
		final int RESEND = 3;
		final int SENDNEXT = 4;
		final int ENDTX = 5;
		final int ABORT = 6;

		boolean nakReceived = false;
		boolean eotAckReceptionTimerInitialized = false;
		String errMsg = null;
		int retryCount = 0;
		int state = -1;
		byte[] data = null;
		long responseWaitTimeOut = 0;
		long eotAckWaitTimeOutValue = 0;

		inStream = new BufferedInputStream(new FileInputStream(fileToProcess));
		state = CONNECT;

		while(true) {
			switch(state) {
			case CONNECT:
				responseWaitTimeOut = System.currentTimeMillis() + 60000;
				while(nakReceived != true) {
					try {
						data = scm.readBytes(handle);
					} catch (SerialComException exp) {
						inStream.close();
						throw exp;
					}

					if(data.length > 0) {
						/* Instead of purging receive buffer and then waiting for NAK, receive all data because
						 * this approach might be faster. The other side might have opened first time and may 
						 * have flushed garbage data. So receive buffer may contain garbage + NAK character. */
						for(int x=0; x < data.length; x++) {
							if(NAK == data[x]) {
								nakReceived = true;
								state = BEGINSEND;
								break;
							}
						}
					}else {
						try {
							Thread.sleep(800);  // delay before next attempt to check NAK arrival
						} catch (InterruptedException e) {
						}
						// abort if timedout while waiting for NAK character
						if((nakReceived != true) && (System.currentTimeMillis() >= responseWaitTimeOut)) {
							errMsg = SerialComErrorMapper.ERR_TIMEOUT_RECEIVER_CONNECT;
							state = ABORT;
							break;
						}
					}
				}
				break;
			case BEGINSEND:
				blockNumber = 1; // Block numbering starts with 1 for the first block sent, not 0.
				assembleBlock();
				try {
					scm.writeBytes(handle, block);
				} catch (SerialComException exp) {
					inStream.close();
					throw exp;
				}
				state = WAITACK;
				break;
			case RESEND:
				if(retryCount > 10) {
					errMsg = SerialComErrorMapper.ERR_MAX_RETRY_REACHED;
					state = ABORT;
					break;
				}
				try {
					scm.writeBytes(handle, block);
				} catch (SerialComException exp) {
					inStream.close();
					throw exp;
				}
				state = WAITACK;
				break;
			case WAITACK:
				responseWaitTimeOut = System.currentTimeMillis() + 60000; // 1 minute
				while(true) {
					// delay before next attempt to read from serial port
					try {
						if(noMoreData != true) {
							Thread.sleep(150);
						}else {
							Thread.sleep(1500);
						}
					} catch (InterruptedException e) {
					}

					// try to read data from serial port
					try {
						data = scm.readBytes(handle);
					} catch (SerialComException exp) {
						inStream.close();
						throw exp;
					}

					/* if data received process it. if long timeout occurred abort otherwise retry reading from serial port.
					 * if nothing received at all abort. */
					if(data.length > 0) {
						break;
					}else {
						if(noMoreData == true) {
							state = ENDTX;
							break;
						}
						if(System.currentTimeMillis() >= responseWaitTimeOut) {
							if(noMoreData == true) {
								errMsg = SerialComErrorMapper.ERR_TIMEOUT_ACKNOWLEDGE_EOT;
							}else {
								errMsg = SerialComErrorMapper.ERR_TIMEOUT_ACKNOWLEDGE_BLOCK;
							}
							state = ABORT;
							break;
						}
					}
				}

				if((state != ABORT) && (state != ENDTX)) {
					if(noMoreData != true) {
						if(data[0] == ACK) {
							state = SENDNEXT;
						}else if(data[0] == NAK) {
							retryCount++;
							state = RESEND;
						}else{
							errMsg = SerialComErrorMapper.ERR_KNOWN_ERROR_OCCURED;
							state = ABORT;
						}
					}else {
						if(data[0] == ACK) {
							inStream.close();
							return true; // successfully sent file, let's go back home happily
						}else{
							if(System.currentTimeMillis() >= eotAckWaitTimeOutValue) {
								errMsg = SerialComErrorMapper.ERR_TIMEOUT_ACKNOWLEDGE_EOT;
								state = ABORT;
							}else {
								state = ENDTX;
							}
						}
					}
				}
				break;
			case SENDNEXT:
				retryCount = 0;
				blockNumber++;
				assembleBlock();
				if(noMoreData == true) {
					state = ENDTX;
					break;
				}
				try {
					scm.writeBytes(handle, block);
				} catch (SerialComException exp) {
					inStream.close();
					throw exp;
				}
				state = WAITACK;
				break;
			case ENDTX:
				if(eotAckReceptionTimerInitialized != true) {
					eotAckWaitTimeOutValue = System.currentTimeMillis() + 60000; // 1 minute
					eotAckReceptionTimerInitialized = true;
				}
				try {
					scm.writeSingleByte(handle, EOT);
				} catch (SerialComException exp) {
					inStream.close();
					throw exp;
				}
				state = WAITACK;
				break;
			case ABORT:
				/* if ioexception occurs, control will not reach here instead exception would have been
				 * thrown already. */
				inStream.close();
				throw new SerialComTimeOutException("sendFile()", errMsg);
			}
		}
	}

	// prepares xmodem block <SOH><blk #><255-blk #><--128 data bytes--><cksum>
	private void assembleBlock() throws IOException {
		int data = 0;
		int x = 0;
		int blockChecksum = 0;

		if(blockNumber > 0xFF) {
			blockNumber = 0x00;
		}

		block[0] = SOH;
		block[1] = (byte) blockNumber;
		block[2] = (byte) ~blockNumber;

		for(x=x+3; x<128+4; x++) {
			data = inStream.read();
			if(data < 0) {
				if(x != 3) {
					// assembling last block with padding
					for(x=x+0; x<128+4; x++) {
						block[x] = SUB;
					}
				}else {
					noMoreData = true;
					return;
				}
			}else {
				block[x] = (byte) data;
			}
		}

		for(x=3; x<131; x++) {
			blockChecksum = (byte)blockChecksum + block[x];
		}
		block[131] = (byte) (blockChecksum % 256);
	}

	/**
	 * <p>For internal use only.</p>
	 * <p>Represents actions to execute in state machine to implement xmodem protocol for receiving files.</p>
	 * 
	 * @throws SerialComException
	 * @throws FileNotFoundException 
	 */
	public boolean receiveFileX() throws SerialComException, FileNotFoundException, IOException {

		// Finite state machine
		final int CONNECT = 0;
		final int RECEIVEDATA = 1;
		final int VERIFY = 2;
		final int REPLY = 3;
		final int ABORT = 4;

		int a = 0;
		int x = 0;
		int z = 0;
		int retryCount = 0;
		int state = -1;
		int blockNumber = 1; //TODO CHK VALID BLK NO
		int blockChecksum = -1;
		int bufferIndex = 0;
		boolean readComplete = false;
		boolean firstBlock = false;
		boolean isCorrupted = false;
		boolean rxDone = false;
		byte[] block = new byte[132];
		byte[] data = null;
		String errMsg = null;

		outStream = new BufferedOutputStream(new FileOutputStream(fileToProcess));

		// Clear receive buffer before start
		try {
			scm.clearPortIOBuffers(handle, true, false);
		} catch (SerialComException e) {
			outStream.close();
			throw e;
		}

		state = CONNECT;
		while(true) {
			System.out.println("state : " + state);
			switch(state) {
			case CONNECT:
				if(retryCount > 10) {
					state = ABORT;
					errMsg = SerialComErrorMapper.ERR_TIMEOUT_TRANSMITTER_CONNECT;
					break;
				}

				try {
					scm.writeSingleByte(handle, NAK); // send NAK to begin tx
					firstBlock = true;
					state = RECEIVEDATA;
				} catch (SerialComException exp) {
					outStream.close();
					throw exp;
				}
				break;
			case RECEIVEDATA:
				if(firstBlock == false) {
					// TODO
				}else {
					/* If anything is not received from port with in 10 seconds (i.e. transmitter has not started sending data)
					 * go back to CONNECT state to send NAK again otherwise as data is received goto REPLY or VERIFY state
					 * based on EOT or data received. */
					for(a=0; a<34; a++) {
						try {
							Thread.sleep(300); // 300*34 = 10.2 seconds
						} catch (InterruptedException e) {
						}

						try {
							data = scm.readBytes(handle);
							System.out.println("length : " + data.length);
						} catch (SerialComException exp) {
							outStream.close();
							throw exp;
						}

						if(data.length > 0) {
							retryCount = 0;                  // reset retry count
							if(data[0] == EOT){            // transmitter sent EOT as very first data !
								state = REPLY;
								isCorrupted = false;
								rxDone = true;
								break;
							}else {
								if(data.length == 132) {   // complete block read
									for(int i=0; i < 132; i++) {
										block[i] = data[i];
									}
									state = VERIFY;
									break;
								}else {                     // partial block read
									readComplete = false;
									bufferIndex = data.length;
									for(int d=0; d < bufferIndex; d++) {
										block[d] = data[d];
									}
									while(true) {
										try {
											Thread.sleep(50);
										} catch (InterruptedException e) {
										}

										try {
											z = 0;
											data = scm.readBytes(handle);
											if(data.length > 0) {
												for(bufferIndex = bufferIndex + 0; bufferIndex < data.length; bufferIndex++) {
													block[bufferIndex] = data[z];
													z++;
												}
												if(bufferIndex > 131) {
													firstBlock = false;
													break;
												}
											}
										} catch (SerialComException exp) {
											outStream.close();
											throw exp;
										}
									}
								}
							}
						}
					}

					if(a > 33){
						state = CONNECT;
						retryCount++;
					}

				}
				break;
			case VERIFY:
				blockChecksum = 0;
				isCorrupted = false;
				// verify block number
				if(block[1] != ~block[2]){
					isCorrupted = true;
					state = REPLY;
					break;
				}
				// verify checksum
				for(x=3; x<131; x++) {
					blockChecksum = (byte)blockChecksum + block[x];
				}
				blockChecksum = (byte) (blockChecksum % 256);
				if(blockChecksum != block[131]){
					isCorrupted = true;
					state = REPLY;
				}
				state = REPLY;
				break;
			case REPLY:
				try {
					if(isCorrupted == false) {
						scm.writeSingleByte(handle, ACK);
					}else {
						scm.writeSingleByte(handle, NAK);
					}
				} catch (SerialComException exp) {
					outStream.close();
					throw exp;
				}
				if(rxDone == false) {
					state = RECEIVEDATA;
				}else {
					outStream.close();
					return true; // file reception successfully finished, let us go back home
				}
				break;
			case ABORT:
				/* if ioexception occurs, control will not reach here instead exception would have been
				 * thrown already. */
				outStream.close();
				throw new SerialComTimeOutException("receiveFile()", errMsg);
			}
		}
	}
}