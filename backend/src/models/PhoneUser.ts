import { ObjectId } from 'mongodb';

export interface PhoneUser {
  _id?: ObjectId;
  phoneNumber: string;
  displayName?: string;
  isVerified: boolean;
  verifyRequestId?: string;
  isInAppAvailable: boolean;
  totalCalls: number;
  lastCallStatus?: string;
  lastCallAt?: Date;
  createdAt: Date;
  updatedAt: Date;
}
