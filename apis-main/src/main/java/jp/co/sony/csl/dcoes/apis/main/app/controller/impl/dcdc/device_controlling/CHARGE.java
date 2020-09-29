package jp.co.sony.csl.dcoes.apis.main.app.controller.impl.dcdc.device_controlling;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jp.co.sony.csl.dcoes.apis.common.Error;
import jp.co.sony.csl.dcoes.apis.main.app.PolicyKeeping;
import jp.co.sony.csl.dcoes.apis.main.app.controller.impl.dcdc.DcdcDeviceControlling;
import jp.co.sony.csl.dcoes.apis.main.app.controller.util.DDCon;
import jp.co.sony.csl.dcoes.apis.main.util.ErrorUtil;

/**
 * デバイスを受電モードにする.
 * グリッド電流指定が規定値より大きい場合は {@link GridCurrentStepping} を呼び出し段階的に変化させる.
 * そうでなければ {@link Checkpoint} を呼び反映を確認する.
 * @author OES Project
 */
public class CHARGE extends AbstractDcdcDeviceControllingCommand {
//	private static final Logger log = LoggerFactory.getLogger(CHARGE.class);

	private Float gridCurrentA_;
	private Float operationGridVoltageV_;
	private Float gridCurrentStepA_;

	/**
	 * インスタンスを生成する.
	 * @param vertx vertx オブジェクト
	 * @param controller 実際にデバイスに命令を送信するオブジェクト
	 * @param params 制御パラメタ.
	 *        - gridCurrentA : グリッド電流値 [{@link Float}]. 必須
	 */
	public CHARGE(Vertx vertx, DcdcDeviceControlling controller, JsonObject params) {
		this(vertx, controller, params, params.getFloat("gridCurrentA"));
	}
	/**
	 * インスタンスを生成する.
	 * @param vertx vertx オブジェクト
	 * @param controller 実際にデバイスに命令を送信するオブジェクト
	 * @param params 制御パラメタ
	 * @param gridCurrentA グリッド電流値. {@code null} 不可
	 */
	public CHARGE(Vertx vertx, DcdcDeviceControlling controller, JsonObject params, Float gridCurrentA) {
		super(vertx, controller, params);
		gridCurrentA_ = gridCurrentA;
	}

	// この処理により動的安全性チェックのスキップを開始しない
	@Override protected boolean startIgnoreDynamicSafetyCheck() { return false; }

	// この処理により動的安全性チェックのスキップを終了する
	@Override protected boolean stopIgnoreDynamicSafetyCheck() { return true; }

	@Override protected void doExecute(Handler<AsyncResult<JsonObject>> completionHandler) {
		if (gridCurrentA_ != null) {
			operationGridVoltageV_ = PolicyKeeping.cache().getFloat("operationGridVoltageV");
			gridCurrentStepA_ = PolicyKeeping.cache().getFloat("gridCurrentStepA");
			if (operationGridVoltageV_ != null && gridCurrentStepA_ != null) {
				execute__(completionHandler);
			} else {
				ErrorUtil.reportAndFail(vertx_, Error.Category.USER, Error.Extent.LOCAL, Error.Level.ERROR, "data deficiency; POLICY.operationGridVoltageV : " + operationGridVoltageV_ + ", POLICY.gridCurrentStepA : " + gridCurrentStepA_, completionHandler);
			}
		} else {
			ErrorUtil.reportAndFail(vertx_, Error.Category.LOGIC, Error.Extent.LOCAL, Error.Level.ERROR, "illegal parameters, gridCurrentA : " + gridCurrentA_, completionHandler);
		}
	}
	private void execute__(Handler<AsyncResult<JsonObject>> completionHandler) {
		if (gridCurrentA_ < gridCurrentStepA_) {
			// 指定値がステップ値より小さいので直接指定する
			controller_.setDcdcMode(DDCon.Mode.CHARGE, operationGridVoltageV_, gridCurrentA_, res -> {
				if (res.succeeded()) {
					new Checkpoint(vertx_, controller_, params_, null, gridCurrentA_).execute(completionHandler);
				} else {
					completionHandler.handle(res);
				}
			});
		} else {
			// 指定値がステップ値より大きいので段階的に変更する
			// まずステップ値で CHARGE モードにし
			controller_.setDcdcMode(DDCon.Mode.CHARGE, operationGridVoltageV_, gridCurrentStepA_, res -> {
				if (res.succeeded()) {
					// 電流ステップ処理に移行する
					new GridCurrentStepping(vertx_, controller_, params_, gridCurrentA_).execute(completionHandler);
				} else {
					completionHandler.handle(res);
				}
			});
		}
	}

}
