/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package jackpal.androidterm.libtermexec.v1;

import android.content.IntentSender;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ResultReceiver;

public interface ITerminal extends IInterface {
    public static final String DESCRIPTOR = "jackpal.androidterm.libtermexec.v1.ITerminal";

    public IntentSender startSession(ParcelFileDescriptor pseudoTerminalMultiplexerFd, ResultReceiver callback) throws RemoteException;

    public static abstract class Stub extends Binder implements ITerminal {
        static final int TRANSACTION_startSession = (IBinder.FIRST_CALL_TRANSACTION + 0);

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static ITerminal asInterface(IBinder obj) {
            if ((obj == null)) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (((iin != null) && (iin instanceof ITerminal))) {
                return ((ITerminal) iin);
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            String descriptor = DESCRIPTOR;
            switch (code) {
                case INTERFACE_TRANSACTION: {
                    reply.writeString(descriptor);
                    return true;
                }
                case TRANSACTION_startSession: {
                    data.enforceInterface(descriptor);
                    ParcelFileDescriptor _arg0;
                    if ((0 != data.readInt())) {
                        _arg0 = ParcelFileDescriptor.CREATOR.createFromParcel(data);
                    } else {
                        _arg0 = null;
                    }
                    ResultReceiver _arg1;
                    if ((0 != data.readInt())) {
                        _arg1 = ResultReceiver.CREATOR.createFromParcel(data);
                    } else {
                        _arg1 = null;
                    }
                    IntentSender _result = this.startSession(_arg0, _arg1);
                    reply.writeNoException();
                    if ((_result != null)) {
                        reply.writeInt(1);
                        _result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }
                default: {
                    return super.onTransact(code, data, reply, flags);
                }
            }
        }

        private static class Proxy implements ITerminal {
            private IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            public String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }

            @Override
            public IntentSender startSession(ParcelFileDescriptor pseudoTerminalMultiplexerFd, ResultReceiver callback) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                IntentSender _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    if ((pseudoTerminalMultiplexerFd != null)) {
                        _data.writeInt(1);
                        pseudoTerminalMultiplexerFd.writeToParcel(_data, 0);
                    } else {
                        _data.writeInt(0);
                    }
                    if ((callback != null)) {
                        _data.writeInt(1);
                        callback.writeToParcel(_data, 0);
                    } else {
                        _data.writeInt(0);
                    }
                    mRemote.transact(Stub.TRANSACTION_startSession, _data, _reply, 0);
                    _reply.readException();
                    if ((0 != _reply.readInt())) {
                        _result = IntentSender.CREATOR.createFromParcel(_reply);
                    } else {
                        _result = null;
                    }
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }
        }
    }
}
