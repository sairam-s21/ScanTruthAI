//
//  CameraService.swift
//  ScanTruthAI
//

import SwiftUI
import AVFoundation

@Observable
@MainActor
final class CameraService: NSObject {
    var isCameraAuthorized: Bool = false
    var isRunning: Bool = false
    var capturedImage: UIImage? = nil
    var isSimulator: Bool = false
    
    let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private var photoContinuation: CheckedContinuation<UIImage?, Never>?
    
    override init() {
        super.init()
        #if targetEnvironment(simulator)
        isSimulator = true
        #else
        isSimulator = false
        #endif
    }
    
    func checkPermissionsAndSetup() {
        #if targetEnvironment(simulator)
        isSimulator = true
        #else
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            isCameraAuthorized = true
            setupSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    self?.isCameraAuthorized = granted
                    if granted {
                        self?.setupSession()
                    }
                }
            }
        default:
            isCameraAuthorized = false
        }
        #endif
    }
    
    private func setupSession() {
        guard !session.isRunning else { return }
        session.beginConfiguration()
        session.sessionPreset = .photo
        
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            return
        }
        
        session.addInput(input)
        
        if session.canAddOutput(photoOutput) {
            photoOutput.maxPhotoDimensions = CMVideoDimensions(width: 3840, height: 2160)
            session.addOutput(photoOutput)
        }
        
        session.commitConfiguration()
        
        let captureSession = self.session
        Task.detached(priority: .userInitiated) {
            captureSession.startRunning()
            await MainActor.run { [weak self] in
                self?.isRunning = true
            }
        }
    }
    
    func stopSession() {
        guard session.isRunning else { return }
        let captureSession = self.session
        Task.detached(priority: .background) {
            captureSession.stopRunning()
            await MainActor.run { [weak self] in
                self?.isRunning = false
            }
        }
    }
    
    func capturePhoto() async -> UIImage? {
        #if targetEnvironment(simulator)
        return nil
        #else
        guard session.isRunning else { return nil }
        
        return await withCheckedContinuation { continuation in
            self.photoContinuation = continuation
            let settings = AVCapturePhotoSettings()
            photoOutput.capturePhoto(with: settings, delegate: self)
        }
        #endif
    }
}

extension CameraService: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        let image: UIImage?
        if let data = photo.fileDataRepresentation() {
            image = UIImage(data: data)
        } else {
            image = nil
        }
        
        Task { @MainActor in
            self.capturedImage = image
            self.photoContinuation?.resume(returning: image)
            self.photoContinuation = nil
        }
    }
}

// MARK: - SwiftUI Preview View
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession
    
    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.videoPreviewLayer.session = session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        return view
    }
    
    func updateUIView(_ uiView: PreviewUIView, context: Context) {}
    
    class PreviewUIView: UIView {
        override class var layerClass: AnyClass {
            AVCaptureVideoPreviewLayer.self
        }
        
        var videoPreviewLayer: AVCaptureVideoPreviewLayer {
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}
